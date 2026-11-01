package balance.tenant.filter;

import balance.tenant.context.TenantContext;
import balance.tenant.service.TenantStatusService;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extrae el tenant_id del JWT (claim "tenant_id") y lo setea en TenantContext.
 * Debe ejecutarse DESPUÉS de que Spring Security valide el JWT.
 *
 * El claim "tenant_id" se configura en Keycloak como Protocol Mapper:
 *   User Attribute "tenant_id" → Token Claim "tenant_id"
 *
 * Tenant 0 = administrador de plataforma (ver PlatformSecurity).
 * Si la empresa está suspendida (tenants.active = false), corta el request con 403 TENANT_SUSPENDED.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final String TENANT_CLAIM = "tenant_id";
    static final String SUSPENDED_BODY =
            "{\"error\":\"TENANT_SUSPENDED\",\"message\":\"La cuenta de esta empresa está suspendida. Contacta a soporte.\"}";

    /** Opcional: los tests de slice (@WebMvcTest) levantan este filtro sin la capa de datos. */
    private final ObjectProvider<TenantStatusService> tenantStatusService;

    public TenantFilter(ObjectProvider<TenantStatusService> tenantStatusService) {
        this.tenantStatusService = tenantStatusService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                Object tenantClaim = jwt.getClaim(TENANT_CLAIM);
                if (tenantClaim != null) {
                    try {
                        Long tenantId = Long.parseLong(tenantClaim.toString());
                        TenantContext.setTenantId(tenantId);
                        TenantStatusService status = tenantStatusService.getIfAvailable();
                        if (status != null && !status.isActive(tenantId)) {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(SUSPENDED_BODY);
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                        // claim malformado — no setear tenant
                    }
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
