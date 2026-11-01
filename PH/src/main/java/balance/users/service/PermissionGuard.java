package balance.users.service;

import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import balance.users.model.AppUser;
import balance.users.model.PermissionModule;
import balance.users.model.Role;
import balance.users.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Guard de acceso granular server-side. PH v2 no tiene ningún equivalente
 * de esto — su restricción por perfil/local es 100% frontend (ver
 * "01. Sistema de Permisos Granulares.md" en el vault de Belopia, hallazgo
 * de seguridad). Este guard cierra ese hueco y además valida tenant, algo
 * que PH v2 (single-tenant) nunca necesitó contemplar.
 *
 * Orden fijo, nunca al revés: tenant -> excepción legacy -> local -> módulo.
 * Confirmado también por AWS Prescriptive Guidance sobre RBAC multi-tenant:
 * la resolución de tenant es un paso de enrutamiento previo e independiente
 * a la evaluación de rol/permiso.
 */
@Service
public class PermissionGuard {

    @Autowired private AppUserRepository userRepository;
    @Autowired private StoreRepository storeRepository;

    /**
     * Verifica acceso a un módulo que no tiene dimensión de local en absoluto
     * (ej. Dashboard: agregado de todos los locales del tenant por diseño,
     * no un endpoint que "omite" un storeId). No confundir con
     * {@link #assertAccess(PermissionModule, Long)} pasando {@code null}
     * — ese caso sí rechaza a usuarios con perfil acotado.
     */
    public void assertAccess(PermissionModule module) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        String keycloakId = currentKeycloakId();

        Optional<AppUser> user = userRepository.findByKeycloakIdAndTenantId(keycloakId, tenantId);
        if (isLegacy(user)) {
            return;
        }

        if (!effectivePermissions(user.get()).contains(module.name())) {
            throw new AccessDeniedException("No tenés acceso a este módulo");
        }
    }

    /**
     * Igual que {@link #assertAccess(PermissionModule)} pero para una pantalla
     * que en la práctica agrupa más de un módulo del catálogo (ej. "Finanzas"
     * cubre TRANSACTIONS+SALARY_PAYMENTS+SUPPLIER_PAYMENTS en una sola pantalla
     * -- ver Sidebar.tsx). Alcanza con tener cualquiera de los módulos listados.
     */
    public void assertAccessAny(PermissionModule... modules) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        String keycloakId = currentKeycloakId();

        Optional<AppUser> user = userRepository.findByKeycloakIdAndTenantId(keycloakId, tenantId);
        if (isLegacy(user)) {
            return;
        }

        Set<String> granted = effectivePermissions(user.get());
        boolean allowed = Arrays.stream(modules).anyMatch(m -> granted.contains(m.name()));
        if (!allowed) {
            throw new AccessDeniedException("No tenés acceso a este módulo");
        }
    }

    /**
     * Verifica acceso a un módulo con dimensión de local. {@code storeId == null}
     * significa "consulta agregada de todos los locales" (ej. reportes sin filtro).
     * Para un usuario con perfil acotado (no legacy) eso se rechaza explícitamente
     * — no se filtra el agregado por sus locales permitidos porque eso requiere
     * cambios en cada service de reporte, fuera de alcance de SPRINT-09. Un usuario
     * legacy (ver excepción abajo) sigue pudiendo pedir el agregado sin filtro,
     * igual que hoy.
     */
    public void assertAccess(PermissionModule module, Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        String keycloakId = currentKeycloakId();

        Optional<AppUser> maybeUser = userRepository.findByKeycloakIdAndTenantId(keycloakId, tenantId);
        if (isLegacy(maybeUser)) {
            return;
        }
        AppUser user = maybeUser.get();

        if (storeId == null) {
            throw new AccessDeniedException("Debés especificar un local");
        }

        // Defensa en profundidad: el storeId también debe pertenecer al tenant,
        // independientemente de que el controller ya lo valide o no.
        Store store = storeRepository.findByIdAndTenantId(storeId, tenantId)
                .orElseThrow(() -> new AccessDeniedException("Acceso denegado"));

        boolean storeAllowed = user.getAccessibleStores().stream()
                .anyMatch(s -> s.getId().equals(store.getId()));
        if (!storeAllowed) {
            throw new AccessDeniedException("No tenés acceso a este local");
        }

        if (!effectivePermissions(user).contains(module.name())) {
            throw new AccessDeniedException("No tenés acceso a este módulo");
        }
    }

    /** Qué turnos de un local puede leer el usuario actual. */
    public enum ShiftReadScope {
        /** Todos los turnos del local (historial completo). */
        ALL,
        /** Solo los turnos que abrió él (más el turno abierto del local, ver ShiftAccessService). */
        OWN
    }

    /**
     * Alcance de lectura de turnos/ventas de un local.
     * <ul>
     *   <li>root (Keycloak) o usuario legacy: {@link ShiftReadScope#ALL}.</li>
     *   <li>Staff con canManageUsers y módulo SALES_HISTORY: ALL (pantalla "Historial ventas" del panel).</li>
     *   <li>Cualquier otro staff con POS o SALES_HISTORY en ese local: {@link ShiftReadScope#OWN}
     *       ("Mis ventas" del cajero). Un cajero nunca ve los turnos ni los montos de otro.</li>
     *   <li>Sin esos módulos, o local fuera de sus locales permitidos: 403.</li>
     * </ul>
     */
    public ShiftReadScope shiftReadScope(Long storeId) {
        if (hasRealmRole("root")) {
            return ShiftReadScope.ALL;
        }
        Long tenantId = TenantSecurityUtils.requireTenantId();
        String keycloakId = currentKeycloakId();

        Optional<AppUser> maybeUser = userRepository.findByKeycloakIdAndTenantId(keycloakId, tenantId);
        if (isLegacy(maybeUser)) {
            return ShiftReadScope.ALL;
        }
        AppUser user = maybeUser.get();

        if (storeId == null) {
            throw new AccessDeniedException("Debés especificar un local");
        }
        Store store = storeRepository.findByIdAndTenantId(storeId, tenantId)
                .orElseThrow(() -> new AccessDeniedException("Acceso denegado"));
        boolean storeAllowed = user.getAccessibleStores().stream()
                .anyMatch(s -> s.getId().equals(store.getId()));
        if (!storeAllowed) {
            throw new AccessDeniedException("No tenés acceso a este local");
        }

        Set<String> granted = effectivePermissions(user);
        boolean manager = user.getRole() != null && user.getRole().isCanManageUsers();
        if (manager && granted.contains(PermissionModule.SALES_HISTORY.name())) {
            return ShiftReadScope.ALL;
        }
        if (granted.contains(PermissionModule.POS.name()) || granted.contains(PermissionModule.SALES_HISTORY.name())) {
            return ShiftReadScope.OWN;
        }
        throw new AccessDeniedException("No tenés acceso a este módulo");
    }

    /** Usuario del token (preferred_username): fuente confiable, a diferencia de un campo del body. */
    public String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String username = jwt.getClaimAsString("preferred_username");
            if (username != null && !username.isBlank()) {
                return username;
            }
        }
        throw new AccessDeniedException("No autenticado");
    }

    private boolean hasRealmRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }

    /** SPRINT-14: el Role es la fuente de verdad de permisos si está asignado.
     * {@link AppUser#getPermissions()} (SPRINT-09) queda como fallback deprecado
     * para usuarios que todavía no tengan Role (no debería pasar tras la
     * migración V5, pero no se asume). */
    private Set<String> effectivePermissions(AppUser user) {
        Role role = user.getRole();
        return role != null ? role.getPermissions() : user.getPermissions();
    }

    /**
     * Un usuario cuenta como "legacy" (acceso total, sin restricción) en dos casos:
     * (a) no tiene fila en app_users para este keycloakId+tenant — hoy es el caso
     *     de TODOS los usuarios reales de Belopia, porque la migración a Contabo
     *     (SPRINT-08C) partió de un schema vacío, sin datos migrados; antes de
     *     SPRINT-09 nada validaba contra app_users para autorizar, así que negar
     *     acceso acá sería una regresión real, no una mejora de seguridad; o
     * (b) tiene fila pero no tiene Role asignado (SPRINT-14) ni datos del
     *     modelo viejo de SPRINT-09 (businessRole/permissions) — nunca tuvo
     *     perfil acotado, sigue con acceso total.
     */
    private boolean isLegacy(Optional<AppUser> maybeUser) {
        if (maybeUser.isEmpty()) {
            return true;
        }
        AppUser user = maybeUser.get();
        return user.getRole() == null
                && user.getBusinessRole() == null
                && user.getPermissions().isEmpty()
                && user.getAccessibleStores().isEmpty();
    }

    private String currentKeycloakId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        throw new AccessDeniedException("No autenticado");
    }
}
