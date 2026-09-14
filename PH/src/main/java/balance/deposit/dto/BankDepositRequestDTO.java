package balance.deposit.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Datos para crear un depósito a partir de los cierres pendientes de un local
 * en un período. {@code depositDate} es opcional -- si no se envía, se usa hoy.
 */
@Getter
@Setter
public class BankDepositRequestDTO {

    @NotNull(message = "El local es obligatorio")
    private Long storeId;

    @NotNull(message = "El inicio del período es obligatorio")
    private LocalDate periodStart;

    @NotNull(message = "El fin del período es obligatorio")
    private LocalDate periodEnd;

    private LocalDate depositDate;

    @NotNull(message = "El monto declarado es obligatorio")
    @DecimalMin(value = "0.01", message = "El monto declarado debe ser mayor a 0")
    private BigDecimal declaredAmount;

    private String notes;

    private String imageUri;

    @NotBlank(message = "El nombre de usuario es obligatorio")
    private String username;
}
