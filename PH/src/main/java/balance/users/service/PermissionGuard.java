package balance.users.service;

import balance.common.enums.AppUserStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.PlatformSecurity;
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
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guard de acceso granular server-side. PH v2 no tiene ningún equivalente
 * de esto — su restricción por perfil/local es 100% frontend (ver
 * "01. Sistema de Permisos Granulares.md" en el vault de Belopia, hallazgo
 * de seguridad). Este guard cierra ese hueco y además valida tenant, algo
 * que PH v2 (single-tenant) nunca necesitó contemplar.
 *
 * Orden fijo, nunca al revés: tenant -> root -> usuario de la empresa -> local -> módulo.
 * Confirmado también por AWS Prescriptive Guidance sobre RBAC multi-tenant:
 * la resolución de tenant es un paso de enrutamiento previo e independiente
 * a la evaluación de rol/permiso.
 *
 * <p><b>Falla cerrado (Etapa 1, P6):</b> una cuenta sin fila en {@code app_users} para la
 * empresa del token no tiene acceso a nada (403), salvo el root de Keycloak, que pasa
 * siempre de forma explícita aunque tenga un Role o locales asignados.
 */
@Service
public class PermissionGuard {

    static final String ROOT_ROLE = "root";

    @Autowired private AppUserRepository userRepository;
    @Autowired private StoreRepository storeRepository;

    /**
     * Locales que el usuario actual puede ver/operar.
     * {@code unrestricted} = todos los locales de la empresa (root o cuenta sin perfil acotado).
     * Si no, solo los ids de {@code storeIds}. Un registro sin local queda fuera para un
     * usuario acotado (falla cerrado).
     */
    public record StoreScope(boolean unrestricted, Set<Long> storeIds) {

        public static StoreScope all() {
            return new StoreScope(true, Set.of());
        }

        public static StoreScope only(Collection<Long> ids) {
            return new StoreScope(false, Set.copyOf(ids));
        }

        public boolean allows(Long storeId) {
            return unrestricted || (storeId != null && storeIds.contains(storeId));
        }

        public boolean allows(Store store) {
            return allows(store != null ? store.getId() : null);
        }
    }

    /**
     * Verifica acceso a un módulo que no tiene dimensión de local en absoluto
     * (ej. Dashboard: agregado de todos los locales del tenant por diseño,
     * no un endpoint que "omite" un storeId). No confundir con
     * {@link #assertAccess(PermissionModule, Long)} pasando {@code null}
     * — ese caso sí rechaza a usuarios con perfil acotado.
     */
    public void assertAccess(PermissionModule module) {
        Optional<AppUser> user = restrictedUser();
        if (user.isEmpty()) {
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
        Optional<AppUser> user = restrictedUser();
        if (user.isEmpty()) {
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
     * Para un usuario con perfil acotado eso se rechaza explícitamente; los listados que
     * sí admiten "todos mis locales" usan {@link #storeScope()} para filtrar.
     */
    public void assertAccess(PermissionModule module, Long storeId) {
        Optional<AppUser> maybeUser = restrictedUser();
        if (maybeUser.isEmpty()) {
            return;
        }
        AppUser user = maybeUser.get();
        requireAllowedStore(user, storeId);
        if (!effectivePermissions(user).contains(module.name())) {
            throw new AccessDeniedException("No tenés acceso a este módulo");
        }
    }

    /**
     * Escritura "de encargado" sobre un módulo con local: editar el catálogo, salidas y
     * ajustes de stock, editar/borrar operaciones y depósitos. Igual que
     * {@link #assertAccess(PermissionModule, Long)} y además exige un Role con
     * canManageUsers (root y cuentas sin perfil acotado pasan siempre). Es la misma regla
     * que aplica el frontend (Revisión 04): el backend no puede ser más laxo que la UI.
     */
    public void assertManage(PermissionModule module, Long storeId) {
        Optional<AppUser> maybeUser = restrictedUser();
        if (maybeUser.isEmpty()) {
            return;
        }
        assertAccess(module, storeId);
        Role role = maybeUser.get().getRole();
        if (role == null || !role.isCanManageUsers()) {
            throw new AccessDeniedException("Solo un encargado puede hacer esta operación");
        }
    }

    /**
     * El local tiene que ser de la empresa y estar entre los permitidos del usuario.
     * No mira módulos: es para recursos que todo usuario de la empresa necesita
     * (ej. el detalle de un local en el selector).
     */
    public void assertStoreAllowed(Long storeId) {
        Optional<AppUser> maybeUser = restrictedUser();
        if (maybeUser.isEmpty()) {
            return;
        }
        requireAllowedStore(maybeUser.get(), storeId);
    }

    /**
     * Locales visibles para el usuario actual (P2/P7/P10): para filtrar listados que
     * admiten "todos mis locales". El administrador de plataforma (tenant 0, sin fila)
     * recibe un alcance vacío en vez de un 403, para no romper su panel.
     */
    public StoreScope storeScope() {
        TenantSecurityUtils.requireTenantId();
        if (isRoot()) {
            return StoreScope.all();
        }
        if (PlatformSecurity.isPlatformAdmin()) {
            return StoreScope.only(Set.of());
        }
        Optional<AppUser> maybeUser = restrictedUser();
        if (maybeUser.isEmpty()) {
            return StoreScope.all();
        }
        return StoreScope.only(maybeUser.get().getAccessibleStores().stream()
                .map(Store::getId)
                .collect(Collectors.toSet()));
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
     *   <li>root (Keycloak) o cuenta sin perfil acotado: {@link ShiftReadScope#ALL}.</li>
     *   <li>Staff con canManageUsers y módulo SALES_HISTORY: ALL (pantalla "Historial ventas" del panel).</li>
     *   <li>Cualquier otro staff con POS o SALES_HISTORY en ese local: {@link ShiftReadScope#OWN}
     *       ("Mis ventas" del cajero). Un cajero nunca ve los turnos ni los montos de otro.</li>
     *   <li>Sin esos módulos, local fuera de sus locales permitidos o sin fila en app_users: 403.</li>
     * </ul>
     */
    public ShiftReadScope shiftReadScope(Long storeId) {
        Optional<AppUser> maybeUser = restrictedUser();
        if (maybeUser.isEmpty()) {
            return ShiftReadScope.ALL;
        }
        AppUser user = maybeUser.get();
        requireAllowedStore(user, storeId);

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

    /**
     * Si el usuario actual gestiona usuarios: root o un Role con canManageUsers. Mismo
     * criterio que {@code RoleService.assertCanManageUsers}. No lanza: sirve para decidir
     * si puede ver datos de OTRO usuario (P3, P9).
     */
    public boolean canManageUsers() {
        if (isRoot()) {
            return true;
        }
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return userRepository.findByKeycloakIdAndTenantId(currentKeycloakId(), tenantId)
                .filter(this::isActive)
                .map(AppUser::getRole)
                .map(Role::isCanManageUsers)
                .orElse(false);
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

    /** Root de la empresa: rol de realm {@code root} en el JWT. Pasa siempre, de forma explícita. */
    public boolean isRoot() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + ROOT_ROLE).equals(a.getAuthority()));
    }

    // ── Internos ─────────────────────────────────────────────────────────────

    /**
     * Resuelve al usuario actual:
     * <ul>
     *   <li>root → vacío (sin restricción), aunque tenga Role o locales asignados;</li>
     *   <li>sin fila en app_users para este keycloakId+tenant → 403 (falla cerrado, P6);</li>
     *   <li>fila suspendida o eliminada → 403 (el token puede seguir vivo unos minutos);</li>
     *   <li>fila sin ningún perfil (sin Role, ni datos del modelo viejo, ni locales) → vacío:
     *       es la cuenta "sin Role" que solo root puede crear (ver AppUserService.create);</li>
     *   <li>cualquier otro caso → el usuario, con perfil acotado.</li>
     * </ul>
     */
    private Optional<AppUser> restrictedUser() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        if (isRoot()) {
            return Optional.empty();
        }
        AppUser user = userRepository.findByKeycloakIdAndTenantId(currentKeycloakId(), tenantId)
                .orElseThrow(() -> new AccessDeniedException("Tu usuario no está habilitado en esta empresa"));
        if (!isActive(user)) {
            throw new AccessDeniedException("Tu usuario no está activo");
        }
        return hasNoProfile(user) ? Optional.empty() : Optional.of(user);
    }

    private void requireAllowedStore(AppUser user, Long storeId) {
        if (storeId == null) {
            throw new AccessDeniedException("Debés especificar un local");
        }
        // Defensa en profundidad: el storeId también debe pertenecer al tenant,
        // independientemente de que el controller ya lo valide o no.
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = storeRepository.findByIdAndTenantId(storeId, tenantId)
                .orElseThrow(() -> new AccessDeniedException("Acceso denegado"));
        boolean storeAllowed = user.getAccessibleStores().stream()
                .anyMatch(s -> s.getId().equals(store.getId()));
        if (!storeAllowed) {
            throw new AccessDeniedException("No tenés acceso a este local");
        }
    }

    private boolean isActive(AppUser user) {
        return user.getStatus() != AppUserStatus.SUSPENDED && user.getStatus() != AppUserStatus.DELETED;
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
     * Fila sin ningún perfil: sin Role (SPRINT-14), sin datos del modelo viejo de SPRINT-09
     * (businessRole/permissions) y sin locales. Hoy solo la crea root a propósito
     * ("acceso total"); queda pendiente de decisión en la Etapa 2 (socios / co-dueños).
     */
    private boolean hasNoProfile(AppUser user) {
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
