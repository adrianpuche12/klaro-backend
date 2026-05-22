package balance.inventory.dto;

import balance.catalog.model.Category;
import balance.inventory.model.InventoryStock;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
public class StockItemDTO {
    private Long stockId;
    private Long productId;
    private String productName;
    private String productSku;
    private String productType;
    private Boolean productActive;
    private BigDecimal price;
    private Integer quantity;
    private Integer minStock;
    private boolean lowStock;
    private String categoryName;
    private String categoryPath;
    private Long categoryId;
    private Long storeId;
    private String storeName;
    private LocalDateTime updatedAt;

    public static StockItemDTO from(InventoryStock stock) {
        StockItemDTO dto = new StockItemDTO();
        dto.stockId       = stock.getId();
        dto.quantity      = stock.getQuantity();
        dto.updatedAt     = stock.getUpdatedAt();

        var p = stock.getProduct();
        dto.productId     = p.getId();
        dto.productName   = p.getName();
        dto.productSku    = p.getSku();
        dto.productType   = p.getType();
        dto.productActive = p.getActive();
        dto.price         = p.getPrice();
        dto.minStock      = p.getMinStock();
        dto.lowStock      = p.getMinStock() > 0 && stock.getQuantity() <= p.getMinStock();

        if (p.getCategory() != null) {
            dto.categoryName = p.getCategory().getName();
            dto.categoryPath = buildPath(p.getCategory());
            dto.categoryId   = p.getCategory().getId();
        }
        if (stock.getStore() != null) {
            dto.storeId   = stock.getStore().getId();
            dto.storeName = stock.getStore().getName();
        }
        return dto;
    }

    private static String buildPath(Category cat) {
        if (cat == null) return null;
        if (cat.getParent() == null) return cat.getName();
        return buildPath(cat.getParent()) + " > " + cat.getName();
    }
}