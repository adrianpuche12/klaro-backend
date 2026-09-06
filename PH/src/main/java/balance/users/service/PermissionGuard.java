package balance.users.service;

import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import balance.users.model.AppUser;
import balance.users.model.PermissionModule;
import balance.users.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Optional;

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

        if (!user.get().getPermissions().contains(module.name())) {
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

        if (!user.getPermissions().contains(module.name())) {
            throw new AccessDeniedException("No tenés acceso a este módulo");
        }
    }

    /**
     * Un usuario cuenta como "legacy" (acceso total, sin restricción) en dos casos:
     * (a) no tiene fila en app_users para este keycloakId+tenant — hoy es el caso
     *     de TODOS los usuarios reales de Belopia, porque la migración a Contabo
     *     (SPRINT-08C) partió de un schema vacío, sin datos migrados; antes de
     *     SPRINT-09 nada validaba contra app_users para autorizar, así que negar
     *     acceso acá sería una regresión real, no una mejora de seguridad; o
     * (b) tiene fila pero fue creada antes de SPRINT-09 — sin businessRole ni
     *     ninguna fila en permissions/accessibleStores.
     */
    private boolean isLegacy(Optional<AppUser> maybeUser) {
        if (maybeUser.isEmpty()) {
            return true;
        }
        AppUser user = maybeUser.get();
        return user.getBusinessRole() == null
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
