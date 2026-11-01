package balance.tenant.filter;

import balance.tenant.context.TenantContext;
import balance.tenant.service.TenantStatusService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantFilterTest {

    private final TenantStatusService status = mock(TenantStatusService.class);

    @SuppressWarnings("unchecked")
    private TenantFilter filter(TenantStatusService service) {
        ObjectProvider<TenantStatusService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return new TenantFilter(provider);
    }

    private static void authenticateWithTenant(Object tenantId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("tenant_id", tenantId)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void activeTenant_passesWithTenantInContext_andClearsAfter() throws Exception {
        authenticateWithTenant(1);
        when(status.isActive(1L)).thenReturn(true);
        AtomicReference<Long> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.getTenantId());

        filter(status).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo(1L);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void suspendedTenant_isRejectedWith403_andChainIsNotCalled() throws Exception {
        authenticateWithTenant(5);
        when(status.isActive(5L)).thenReturn(false);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(status).doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("TENANT_SUSPENDED");
        verify(chain, never()).doFilter(any(), any());
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void withoutStatusService_requestPasses() throws Exception {
        authenticateWithTenant(5);
        FilterChain chain = mock(FilterChain.class);
        filter(null).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }
}
