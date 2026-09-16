package balance.sales.dto;

import balance.sales.model.ShiftReconciliation;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
public class ShiftReconciliationResponseDTO {

    private Long shiftId;
    private BigDecimal openingCash;
    private BigDecimal declaredCash;
    private BigDecimal expectedCash;
    private BigDecimal difference;
    private LocalDateTime createdAt;

    public static ShiftReconciliationResponseDTO from(ShiftReconciliation r) {
        ShiftReconciliationResponseDTO dto = new ShiftReconciliationResponseDTO();
        dto.shiftId      = r.getShift() != null ? r.getShift().getId() : null;
        dto.openingCash  = r.getOpeningCash();
        dto.declaredCash = r.getDeclaredCash();
        dto.expectedCash = r.getExpectedCash();
        dto.difference   = r.getDifference();
        dto.createdAt    = r.getCreatedAt();
        return dto;
    }
}
