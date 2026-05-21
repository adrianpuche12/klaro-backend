package balance.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class DailySummaryDTO {
    private LocalDate date;
    private Long storeId;
    private String storeName;
    private long totalSales;
    private BigDecimal totalSubtotal;
    private BigDecimal totalIsv;
    private BigDecimal totalAmount;
    private BigDecimal totalCash;
    private BigDecimal totalCard;
    private List<ProductSummaryItem> productSummary;

    public DailySummaryDTO(LocalDate date, Long storeId, String storeName,
                           long totalSales, BigDecimal totalSubtotal, BigDecimal totalIsv,
                           BigDecimal totalAmount, BigDecimal totalCash, BigDecimal totalCard,
                           List<ProductSummaryItem> productSummary) {
        this.date           = date;
        this.storeId        = storeId;
        this.storeName      = storeName;
        this.totalSales     = totalSales;
        this.totalSubtotal  = totalSubtotal;
        this.totalIsv       = totalIsv;
        this.totalAmount    = totalAmount;
        this.totalCash      = totalCash;
        this.totalCard      = totalCard;
        this.productSummary = productSummary;
    }

    public static class ProductSummaryItem {
        private Long productId;
        private String productName;
        private int quantity;
        private BigDecimal subtotal;

        public ProductSummaryItem(Long productId, String productName, int quantity, BigDecimal subtotal) {
            this.productId   = productId;
            this.productName = productName;
            this.quantity    = quantity;
            this.subtotal    = subtotal;
        }

        public Long getProductId()       { return productId; }
        public String getProductName()   { return productName; }
        public int getQuantity()         { return quantity; }
        public BigDecimal getSubtotal()  { return subtotal; }
    }

    public LocalDate getDate()                       { return date; }
    public Long getStoreId()                         { return storeId; }
    public String getStoreName()                     { return storeName; }
    public long getTotalSales()                      { return totalSales; }
    public BigDecimal getTotalSubtotal()             { return totalSubtotal; }
    public BigDecimal getTotalIsv()                  { return totalIsv; }
    public BigDecimal getTotalAmount()               { return totalAmount; }
    public BigDecimal getTotalCash()                 { return totalCash; }
    public BigDecimal getTotalCard()                 { return totalCard; }
    public List<ProductSummaryItem> getProductSummary() { return productSummary; }
}
