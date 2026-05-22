package balance.tax.dto;

import balance.tax.model.Tax;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
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
        dto.id         = t.getId();
        dto.name       = t.getName();
        dto.rate       = t.getRate();
        dto.type       = t.getType();
        dto.appliesTo  = t.getAppliesTo();
        dto.categoryId = t.getCategoryId();
        dto.productId  = t.getProductId();
        dto.active     = t.getActive();
        dto.createdAt  = t.getCreatedAt();
        return dto;
    }
}
