package balance.tenant.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSecurityTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", roles));
    }

    @Test
    void tenantZeroWithPlatformRole_isPlatformAdmin() {
        TenantContext.setTenantId(0L);
        authenticate("ROLE_platform");
        assertThat(PlatformSecurity.isPlatformAdmin()).isTrue();
    }

    @Test
    void tenantZeroWithoutPlatformRole_isNot() {
        TenantContext.setTenantId(0L);
        authenticate("ROLE_root");
        assertThat(PlatformSecurity.isPlatformAdmin()).isFalse();
    }

    @Test
    void platformRoleInARealTenant_isNot() {
        TenantContext.setTenantId(1L);
        authenticate("ROLE_platform");
        assertThat(PlatformSecurity.isPlatformAdmin()).isFalse();
    }

    @Test
    void noTenantOrNoAuthentication_isNot() {
        authenticate("ROLE_platform");
        assertThat(PlatformSecurity.isPlatformAdmin()).isFalse();
        SecurityContextHolder.clearContext();
        TenantContext.setTenantId(0L);
        assertThat(PlatformSecurity.isPlatformAdmin()).isFalse();
    }

    @Test
    void runAs_restoresPreviousTenant_evenOnException() {
        TenantContext.setTenantId(1L);
        assertThat(TenantContext.runAs(9L, TenantContext::getTenantId)).isEqualTo(9L);
        assertThat(TenantContext.getTenantId()).isEqualTo(1L);
        try {
            TenantContext.runAs(9L, () -> { throw new IllegalStateException("x"); });
        } catch (IllegalStateException ignored) {
            // esperado
        }
        assertThat(TenantContext.getTenantId()).isEqualTo(1L);
        TenantContext.clear();
        TenantContext.runAs(9L, () -> null);
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
