package balance.tenant.context;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Identifica al administrador de plataforma (SPRINT-16).
 *
 * Exige DOS condiciones independientes:
 * <ol>
 *   <li>tenant_id = 0 en el JWT (ninguna empresa puede crear usuarios con tenant 0:
 *       KeycloakAdminService asigna siempre el tenant de quien crea), y</li>
 *   <li>rol de realm "platform" en Keycloak.</li>
 * </ol>
 */
public final class PlatformSecurity {

    public static final String PLATFORM_ROLE = "ROLE_platform";

    private PlatformSecurity() {}

    public static boolean isPlatformAdmin() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId != TenantContext.PLATFORM_TENANT_ID) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> PLATFORM_ROLE.equals(a.getAuthority()));
    }
}
