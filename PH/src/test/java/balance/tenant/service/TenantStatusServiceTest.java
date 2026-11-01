package balance.tenant.service;

import balance.tenant.model.Tenant;
import balance.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantStatusServiceTest {

    private final TenantRepository repo = mock(TenantRepository.class);

    private static Tenant tenant(boolean active) {
        Tenant t = new Tenant();
        t.setActive(active);
        return t;
    }

    @Test
    void platformAndNull_areAlwaysActive_withoutQueryingTheDatabase() {
        TenantStatusService service = new TenantStatusService(repo);
        assertThat(service.isActive(0L)).isTrue();
        assertThat(service.isActive(null)).isTrue();
        verify(repo, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void suspendedTenant_isInactive() {
        when(repo.findById(5L)).thenReturn(Optional.of(tenant(false)));
        assertThat(new TenantStatusService(repo).isActive(5L)).isFalse();
    }

    @Test
    void tenantWithoutRow_isActive() {
        when(repo.findById(7L)).thenReturn(Optional.empty());
        assertThat(new TenantStatusService(repo).isActive(7L)).isTrue();
    }

    @Test
    void result_isCachedUntilTtl_andEvictForcesReload() {
        Instant start = Instant.parse("2026-11-01T12:00:00Z");
        Clock fixed = Clock.fixed(start, ZoneOffset.UTC);
        when(repo.findById(5L)).thenReturn(Optional.of(tenant(true)));
        TenantStatusService service = new TenantStatusService(repo, fixed);

        service.isActive(5L);
        service.isActive(5L);
        verify(repo, times(1)).findById(5L);

        service.evict(5L);
        service.isActive(5L);
        verify(repo, times(2)).findById(5L);

        TenantStatusService later = new TenantStatusService(repo, Clock.fixed(start.plus(TenantStatusService.TTL).plus(Duration.ofSeconds(1)), ZoneOffset.UTC));
        later.isActive(5L);
        verify(repo, times(3)).findById(5L);
    }
}
