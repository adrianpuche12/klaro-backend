package balance.sales.dto;

import balance.sales.model.ShiftExpense;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
public class ShiftExpenseResponseDTO {

    private Long id;
    private Long shiftId;
    private BigDecimal amount;
    private String reason;
    private String username;
    private LocalDateTime createdAt;

    public static ShiftExpenseResponseDTO from(ShiftExpense e) {
        ShiftExpenseResponseDTO dto = new ShiftExpenseResponseDTO();
        dto.id        = e.getId();
        dto.shiftId   = e.getShift() != null ? e.getShift().getId() : null;
        dto.amount    = e.getAmount();
        dto.reason    = e.getReason();
        dto.username  = e.getUsername();
        dto.createdAt = e.getCreatedAt();
        return dto;
    }
}
