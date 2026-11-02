package balance.reports;

import balance.tenant.dto.TenantConfigDTO;
import balance.tenant.service.TenantConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Arma el {@link ReportContext} con la configuración de la empresa actual. */
@Component
public class ReportContextFactory {

    @Autowired private TenantConfigService tenantConfigService;

    public ReportContext current() {
        TenantConfigDTO cfg = tenantConfigService.getConfig();
        ZoneId zone = tenantConfigService.zoneId();
        return new ReportContext(
                cfg.getCompanyName(),
                cfg.getAddress(),
                cfg.getPhone(),
                cfg.getCurrency(),
                zone,
                LocalDateTime.now(zone));
    }
}
