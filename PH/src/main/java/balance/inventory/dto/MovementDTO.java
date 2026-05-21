package balance.inventory.dto;

import balance.inventory.model.InventoryMovement;

import java.time.LocalDateTime;

public class MovementDTO {

    private Long id;
    private String type;
    private Integer quantity;
    private String reason;
    private String notes;
    private String username;
    private String performedBy;
    private String source;
    private Long productId;
    private String productName;
    private Long storeId;
    private LocalDateTime createdAt;

    public static MovementDTO from(InventoryMovement m) {
        MovementDTO dto = new MovementDTO();
        dto.id          = m.getId();
        dto.type        = m.getType();
        dto.quantity    = m.getQuantity();
        dto.reason      = m.getReason();
        dto.notes       = m.getNotes();
        dto.username    = m.getUsername();
        dto.performedBy = m.getPerformedBy();
        dto.source      = m.getSource();
        dto.createdAt   = m.getCreatedAt();
        if (m.getProduct() != null) {
            dto.productId   = m.getProduct().getId();
            dto.productName = m.getProduct().getName();
        }
        if (m.getStore() != null) dto.storeId = m.getStore().getId();
        return dto;
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public Integer getQuantity() { return quantity; }
    public String getReason() { return reason; }
    public String getNotes() { return notes; }
    public String getUsername() { return username; }
    public String getPerformedBy() { return performedBy; }
    public String getSource() { return source; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public Long getStoreId() { return storeId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
