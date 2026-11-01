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

    /** Sin default de ningún cliente: el alta de empresa la exige (SPRINT-16). */
    @Column(length = 3)
    private String currency;

    /** Zona horaria neutra si la empresa todavía no configuró la suya (nunca la de un cliente). */
    public static final String DEFAULT_TIMEZONE = "UTC";

    @Column(length = 50)
    private String timezone = DEFAULT_TIMEZONE;

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
