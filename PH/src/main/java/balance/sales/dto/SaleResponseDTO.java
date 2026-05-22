package balance.sales.dto;

import balance.sales.model.Sale;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
public class SaleResponseDTO {
    private Long id;
    private Long shiftId;
    private String shiftCode;
    private Long storeId;
    private String storeName;
    private String username;
    private LocalDate saleDate;
    private String status;
    private BigDecimal subtotal;
    private BigDecimal isv;
    private BigDecimal total;
    private String paymentMethod;
    private BigDecimal cashAmount;
    private BigDecimal cardAmount;
    private List<SaleItemDTO> items;
    private LocalDateTime createdAt;

    public static SaleResponseDTO from(Sale sale) {
        SaleResponseDTO dto = new SaleResponseDTO();
        dto.id            = sale.getId();
        dto.username      = sale.getUsername();
        dto.saleDate      = sale.getSaleDate();
        dto.status        = sale.getStatus() != null ? sale.getStatus().name() : null;
        dto.subtotal      = sale.getSubtotal();
        dto.isv           = sale.getIsv();
        dto.total         = sale.getTotal();
        dto.paymentMethod = sale.getPaymentMethod();
        dto.cashAmount    = sale.getCashAmount();
        dto.cardAmount    = sale.getCardAmount();
        dto.createdAt     = sale.getCreatedAt();
        dto.items         = sale.getItems().stream().map(SaleItemDTO::from).toList();
        if (sale.getShift() != null) {
            dto.shiftId   = sale.getShift().getId();
            dto.shiftCode = sale.getShift().getCode();
        }
        if (sale.getStore() != null) {
            dto.storeId   = sale.getStore().getId();
            dto.storeName = sale.getStore().getName();
        }
        return dto;
    }
}