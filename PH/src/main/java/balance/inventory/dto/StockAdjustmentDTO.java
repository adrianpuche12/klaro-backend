package balance.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class StockAdjustmentDTO {

    @NotNull(message = "El producto es obligatorio")
    private Long productId;

    @NotBlank(message = "El tipo es obligatorio")
    private String type;

    @NotNull
    @Min(value = 1, message = "La cantidad debe ser mayor a 0")
    private Integer quantity;

    private String reason;
    private String notes;
    private String username;
    private String source = "MANUAL";
}
