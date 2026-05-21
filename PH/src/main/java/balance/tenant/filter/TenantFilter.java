package balance.tenant.filter;

import balance.tenant.context.TenantContext;
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
 * ROOT usa tenant_id = 0.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final String TENANT_CLAIM = "tenant_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                // Leer tenant_id del JWT claim
                Object tenantClaim = jwt.getClaim(TENANT_CLAIM);
                if (tenantClaim != null) {
                    try {
                        Long tenantId = Long.parseLong(tenantClaim.toString());
                        TenantContext.setTenantId(tenantId);
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
