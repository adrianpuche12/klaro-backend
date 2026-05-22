package balance.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter @Setter
public class ProductRequestDTO {

    @NotBlank(message = "El nombre es obligatorio")
    private String name;

    private String sku;

    private String type = "SIMPLE";

    @NotNull(message = "El precio es obligatorio")
    @DecimalMin(value = "0.00", message = "El precio no puede ser negativo")
    private BigDecimal price;

    private Integer minStock = 0;

    private String description;

    private Long categoryId;
}
