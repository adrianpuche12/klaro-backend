package balance.operations.dto;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO unificado para el listado de operaciones del panel admin.
 * Representa cualquier tipo: CLOSING, SALE, SUPPLIER, SALARY, GASTO_ADMIN, TRANSACTION.
 */
@Getter
public class OperationDTO {

    private Long id;
    private String type;
    private LocalDate date;
    private BigDecimal amount;
    private Long storeId;
    private String storeName;
    private String username;
    private String description;
    private String status;
    private String paymentMethod;
    private String imageUri;

    public static OperationDTO of(String type, Long id, LocalDate date, BigDecimal amount,
                                   Long storeId, String storeName, String username,
                                   String description, String status, String paymentMethod,
                                   String imageUri) {
        OperationDTO dto = new OperationDTO();
        dto.type          = type;
        dto.id            = id;
        dto.date          = date;
        dto.amount        = amount;
        dto.storeId       = storeId;
        dto.storeName     = storeName;
        dto.username      = username;
        dto.description   = description;
        dto.status        = status;
        dto.paymentMethod = paymentMethod;
        dto.imageUri      = imageUri;
        return dto;
    }
}
