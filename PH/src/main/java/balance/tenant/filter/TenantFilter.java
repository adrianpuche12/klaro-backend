package balance.tenant.filter;

import balance.tenant.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Intercepta cada request HTTP y extrae el tenant_id del header X-Tenant-ID.
 * Setea el valor en TenantContext para que los servicios puedan leerlo.
 *
 * NOTA: En Sprint 2 este filtro será reemplazado por extracción del JWT claim "tenant_id".
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String tenantHeader = request.getHeader(TENANT_HEADER);
            if (tenantHeader != null && !tenantHeader.isBlank()) {
                try {
                    TenantContext.setTenantId(Long.parseLong(tenantHeader));
                } catch (NumberFormatException ignored) {
                    // Header inválido — no setear tenant
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
