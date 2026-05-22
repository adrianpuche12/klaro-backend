package balance.sales.dto;

import balance.sales.model.SaleItem;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class SaleItemDTO {
    private Long id;
    private Long productId;
    private String productName;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal subtotal;

    public static SaleItemDTO from(SaleItem item) {
        SaleItemDTO dto = new SaleItemDTO();
        dto.id          = item.getId();
        dto.productName = item.getProductNameSnapshot();
        dto.unitPrice   = item.getUnitPriceSnapshot();
        dto.quantity    = item.getQuantity();
        dto.subtotal    = item.getSubtotal();
        if (item.getProduct() != null) dto.productId = item.getProduct().getId();
        return dto;
    }
}