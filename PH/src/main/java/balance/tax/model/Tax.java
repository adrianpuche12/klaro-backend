package balance.tax.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "taxes")
public class Tax {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Nombre del impuesto: "ISV", "IVA", "IGV" */
    @Column(nullable = false, length = 50)
    private String name;

    /** Tasa en porcentaje: 15.00 = 15% */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    /** PERCENTAGE | FIXED */
    @Column(nullable = false, length = 20)
    private String type = "PERCENTAGE";

    /** ALL | CATEGORY | PRODUCT */
    @Column(name = "applies_to", nullable = false, length = 20)
    private String appliesTo = "ALL";

    /** Solo si appliesTo = CATEGORY */
    @Column(name = "category_id")
    private Long categoryId;

    /** Solo si appliesTo = PRODUCT */
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAppliesTo() { return appliesTo; }
    public void setAppliesTo(String appliesTo) { this.appliesTo = appliesTo; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
