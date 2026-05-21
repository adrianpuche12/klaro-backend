package balance.tax.dto;

import balance.tax.model.Tax;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TaxDTO {

    private Long id;
    private String name;
    private BigDecimal rate;
    private String type;
    private String appliesTo;
    private Long categoryId;
    private Long productId;
    private Boolean active;
    private LocalDateTime createdAt;

    public static TaxDTO from(Tax t) {
        TaxDTO dto = new TaxDTO();
        dto.id          = t.getId();
        dto.name        = t.getName();
        dto.rate        = t.getRate();
        dto.type        = t.getType();
        dto.appliesTo   = t.getAppliesTo();
        dto.categoryId  = t.getCategoryId();
        dto.productId   = t.getProductId();
        dto.active      = t.getActive();
        dto.createdAt   = t.getCreatedAt();
        return dto;
    }

    public Long getId() { return id; }
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
    public LocalDateTime getCreatedAt() { return createdAt; }
}
