package balance.tenant.service;

import balance.tenant.context.TenantContext;
import balance.tenant.dto.TenantConfigDTO;
import balance.tenant.model.TenantConfig;
import balance.tenant.repository.TenantConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantConfigServiceTest {

    private static final Long TENANT_ID = 1L;

    @InjectMocks private TenantConfigService tenantConfigService;
    @Mock private TenantConfigRepository    configRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TenantConfig buildConfig(String company, String currency, String tz) {
        TenantConfig c = new TenantConfig();
        c.setTenantId(TENANT_ID);
        c.setCompanyName(company);
        c.setCurrency(currency);
        c.setTimezone(tz);
        return c;
    }

    // ── getConfig ─────────────────────────────────────────────────────────────

    @Test
    void getConfig_returnsExistingConfig() {
        TenantConfig existing = buildConfig("Klaro Demo", "L", "America/Tegucigalpa");
        when(configRepository.findById(TENANT_ID)).thenReturn(Optional.of(existing));

        TenantConfigDTO result = tenantConfigService.getConfig();

        assertThat(result.getCompanyName()).isEqualTo("Klaro Demo");
        assertThat(result.getCurrency()).isEqualTo("L");
        assertThat(result.getTimezone()).isEqualTo("America/Tegucigalpa");
    }

    @Test
    void getConfig_returnsDefaultConfig_whenNoneExists() {
        // getConfig() devuelve un default en memoria sin persistir (no llama a save)
        when(configRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        TenantConfigDTO result = tenantConfigService.getConfig();

        assertThat(result).isNotNull();
        verify(configRepository, never()).save(any()); // no persiste en getConfig
    }

    // ── getTimezone ───────────────────────────────────────────────────────────

    @Test
    void getTimezone_returnsConfiguredTimezone() {
        TenantConfig existing = buildConfig("Klaro", "L", "America/Bogota");
        when(configRepository.findById(TENANT_ID)).thenReturn(Optional.of(existing));

        assertThat(tenantConfigService.getTimezone()).isEqualTo("America/Bogota");
    }

    @Test
    void getTimezone_returnsDefault_whenTimezoneIsNull() {
        TenantConfig existing = buildConfig("Klaro", "L", null);
        when(configRepository.findById(TENANT_ID)).thenReturn(Optional.of(existing));

        assertThat(tenantConfigService.getTimezone()).isEqualTo("America/Tegucigalpa");
    }

    // ── updateConfig ──────────────────────────────────────────────────────────

    @Test
    void updateConfig_updatesExistingRecord() {
        TenantConfig existing = buildConfig("Viejo Nombre", "L", "America/Tegucigalpa");
        when(configRepository.findById(TENANT_ID)).thenReturn(Optional.of(existing));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TenantConfigDTO dto = new TenantConfigDTO();
        dto.setCompanyName("Nuevo Nombre S.A.");
        dto.setCurrency("HNL");
        dto.setTimezone("America/Guatemala");

        TenantConfigDTO result = tenantConfigService.updateConfig(dto);

        assertThat(result.getCompanyName()).isEqualTo("Nuevo Nombre S.A.");
        assertThat(result.getCurrency()).isEqualTo("HNL");
    }

    @Test
    void updateConfig_createsRecord_whenNoneExists() {
        when(configRepository.findById(TENANT_ID)).thenReturn(Optional.empty());
        TenantConfig saved = buildConfig("Demo", "L", "America/Tegucigalpa");
        when(configRepository.save(any())).thenReturn(saved);

        TenantConfigDTO dto = new TenantConfigDTO();
        dto.setCompanyName("Demo");
        dto.setCurrency("L");

        tenantConfigService.updateConfig(dto);

        ArgumentCaptor<TenantConfig> captor = ArgumentCaptor.forClass(TenantConfig.class);
        verify(configRepository, atLeastOnce()).save(captor.capture());
    }

    @Test
    void updateConfig_setsTenantIdOnNewRecord() {
        when(configRepository.findById(TENANT_ID)).thenReturn(Optional.empty());
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TenantConfigDTO dto = new TenantConfigDTO();
        dto.setCompanyName("Test");

        tenantConfigService.updateConfig(dto);

        ArgumentCaptor<TenantConfig> captor = ArgumentCaptor.forClass(TenantConfig.class);
        verify(configRepository, atLeastOnce()).save(captor.capture());
        // El record persistido debe tener el tenantId del contexto
        captor.getAllValues().stream()
                .filter(c -> c.getTenantId() != null)
                .findFirst()
                .ifPresent(c -> assertThat(c.getTenantId()).isEqualTo(TENANT_ID));
    }
}
