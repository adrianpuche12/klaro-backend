package balance.tenant.dto;

import balance.tenant.model.TenantConfig;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter @Setter
public class TenantConfigDTO {

    private String currency;
    private String timezone;
    private String companyName;
    private String companyLogo;
    private String address;
    private String phone;
    private BigDecimal cardSurchargeRate;

    public static TenantConfigDTO from(TenantConfig c) {
        TenantConfigDTO dto = new TenantConfigDTO();
        dto.currency          = c.getCurrency();
        dto.timezone          = c.getTimezone();
        dto.companyName       = c.getCompanyName();
        dto.companyLogo       = c.getCompanyLogo();
        dto.address           = c.getAddress();
        dto.phone             = c.getPhone();
        dto.cardSurchargeRate = c.getCardSurchargeRate();
        return dto;
    }
}
