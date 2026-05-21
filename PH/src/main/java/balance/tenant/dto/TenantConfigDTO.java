package balance.tenant.dto;

import balance.tenant.model.TenantConfig;

public class TenantConfigDTO {

    private String currency;
    private String timezone;
    private String companyName;
    private String companyLogo;
    private String address;
    private String phone;

    public static TenantConfigDTO from(TenantConfig c) {
        TenantConfigDTO dto = new TenantConfigDTO();
        dto.currency    = c.getCurrency();
        dto.timezone    = c.getTimezone();
        dto.companyName = c.getCompanyName();
        dto.companyLogo = c.getCompanyLogo();
        dto.address     = c.getAddress();
        dto.phone       = c.getPhone();
        return dto;
    }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getCompanyLogo() { return companyLogo; }
    public void setCompanyLogo(String companyLogo) { this.companyLogo = companyLogo; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
