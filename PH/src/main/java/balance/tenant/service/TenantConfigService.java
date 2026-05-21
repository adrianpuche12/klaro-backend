package balance.tenant.service;

import balance.tenant.context.TenantSecurityUtils;
import balance.tenant.dto.TenantConfigDTO;
import balance.tenant.model.TenantConfig;
import balance.tenant.repository.TenantConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantConfigService {

    @Autowired
    private TenantConfigRepository configRepository;

    public TenantConfigDTO getConfig() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantConfig config = configRepository.findById(tenantId)
                .orElseGet(() -> defaultConfig(tenantId));
        return TenantConfigDTO.from(config);
    }

    @Transactional
    public TenantConfigDTO updateConfig(TenantConfigDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantConfig config = configRepository.findById(tenantId)
                .orElseGet(() -> defaultConfig(tenantId));

        if (dto.getCurrency()    != null) config.setCurrency(dto.getCurrency());
        if (dto.getTimezone()    != null) config.setTimezone(dto.getTimezone());
        if (dto.getCompanyName() != null) config.setCompanyName(dto.getCompanyName());
        if (dto.getCompanyLogo() != null) config.setCompanyLogo(dto.getCompanyLogo());
        if (dto.getAddress()     != null) config.setAddress(dto.getAddress());
        if (dto.getPhone()       != null) config.setPhone(dto.getPhone());

        return TenantConfigDTO.from(configRepository.save(config));
    }

    /** Devuelve la zona horaria del tenant, con fallback a Honduras. */
    public String getTimezone() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return configRepository.findById(tenantId)
                .map(TenantConfig::getTimezone)
                .orElse("America/Tegucigalpa");
    }

    private TenantConfig defaultConfig(Long tenantId) {
        TenantConfig c = new TenantConfig();
        c.setTenantId(tenantId);
        return c;
    }
}
