package balance.tenant.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

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

    /** Fracción (0.03 = 3%) aplicada al monto pagado con tarjeta. Null = sin recargo -- SPRINT-12. */
    @Column(name = "card_surcharge_rate", precision = 5, scale = 4)
    private BigDecimal cardSurchargeRate;
}
