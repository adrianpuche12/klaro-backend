package balance.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class StockAdjustmentDTO {

    @NotNull(message = "El producto es obligatorio")
    private Long productId;

    // ENTRADA | SALIDA | AJUSTE
    @NotBlank(message = "El tipo es obligatorio")
    private String type;

    @NotNull
    @Min(value = 1, message = "La cantidad debe ser mayor a 0")
    private Integer quantity;

    private String reason;
    private String notes;
    private String username;
    /** Origen del movimiento: SALE | CANCEL | MANUAL | SYSTEM */
    private String source = "MANUAL";

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
