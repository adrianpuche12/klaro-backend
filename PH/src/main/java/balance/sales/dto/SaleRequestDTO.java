package balance.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class SaleRequestDTO {

    @NotBlank(message = "El usuario es obligatorio")
    private String username;

    @NotEmpty(message = "La venta debe tener al menos un producto")
    private List<SaleItemRequestDTO> items;

    private String paymentMethod = "CASH";
    private BigDecimal cashAmount;
    private BigDecimal cardAmount;
}
