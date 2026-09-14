package balance.deposit.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edición de un depósito ya creado: fecha, monto declarado, notas, comprobante.
 * Todos los campos son opcionales -- solo se actualiza lo que venga no-nulo.
 */
@Getter
@Setter
public class BankDepositUpdateDTO {

    private LocalDate depositDate;

    private BigDecimal declaredAmount;

    private String notes;

    private String imageUri;
}
