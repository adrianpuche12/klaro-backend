package balance.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

public class SaleRequestDTO {

    @NotBlank(message = "El usuario es obligatorio")
    private String username;

    @NotEmpty(message = "La venta debe tener al menos un producto")
    private List<SaleItemRequestDTO> items;

    private String paymentMethod = "CASH"; // CASH | CARD | MIXED
    private BigDecimal cashAmount;
    private BigDecimal cardAmount;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public List<SaleItemRequestDTO> getItems() { return items; }
    public void setItems(List<SaleItemRequestDTO> items) { this.items = items; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public BigDecimal getCashAmount() { return cashAmount; }
    public void setCashAmount(BigDecimal cashAmount) { this.cashAmount = cashAmount; }

    public BigDecimal getCardAmount() { return cardAmount; }
    public void setCardAmount(BigDecimal cardAmount) { this.cardAmount = cardAmount; }
}
