package balance.tenant.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tenant_config")
public class TenantConfig {

    @Id
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(length = 3)
    private String currency = "HNL";

    @Column(length = 50)
    private String timezone = "America/Tegucigalpa";

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "company_logo", length = 500)
    private String companyLogo;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 30)
    private String phone;
}
