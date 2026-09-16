package balance.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class DailyClosingResponseDTO {
    private Long shiftId;
    private String shiftCode;
    private LocalDate date;
    private Long storeId;
    private String storeName;
    private long salesConfirmed;
    private BigDecimal totalAmount;
    private Long closingDepositId;
    private String message;
    /** Null si el cierre no incluyó reconciliación de caja (fondo/contado no declarados) -- SPRINT-12. */
    private ShiftReconciliationResponseDTO reconciliation;

    public DailyClosingResponseDTO(Long shiftId, String shiftCode, LocalDate date,
                                   Long storeId, String storeName, long salesConfirmed,
                                   BigDecimal totalAmount, Long closingDepositId) {
        this(shiftId, shiftCode, date, storeId, storeName, salesConfirmed, totalAmount, closingDepositId, null);
    }

    public DailyClosingResponseDTO(Long shiftId, String shiftCode, LocalDate date,
                                   Long storeId, String storeName, long salesConfirmed,
                                   BigDecimal totalAmount, Long closingDepositId,
                                   ShiftReconciliationResponseDTO reconciliation) {
        this.shiftId          = shiftId;
        this.shiftCode        = shiftCode;
        this.date             = date;
        this.storeId          = storeId;
        this.storeName        = storeName;
        this.salesConfirmed   = salesConfirmed;
        this.totalAmount      = totalAmount;
        this.closingDepositId = closingDepositId;
        this.message          = "Cierre de turno completado. " + salesConfirmed + " ventas confirmadas.";
        this.reconciliation   = reconciliation;
    }

    public Long getShiftId() { return shiftId; }
    public String getShiftCode() { return shiftCode; }
    public LocalDate getDate() { return date; }
    public Long getStoreId() { return storeId; }
    public String getStoreName() { return storeName; }
    public long getSalesConfirmed() { return salesConfirmed; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Long getClosingDepositId() { return closingDepositId; }
    public String getMessage() { return message; }
    public ShiftReconciliationResponseDTO getReconciliation() { return reconciliation; }
}
