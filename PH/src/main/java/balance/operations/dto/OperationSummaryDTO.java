package balance.operations.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public class OperationSummaryDTO {

    private LocalDate from;
    private LocalDate to;
    private int totalOperations;
    private BigDecimal totalIncome;
    private BigDecimal totalExpense;
    private BigDecimal netBalance;
    private BigDecimal totalCash;
    private BigDecimal totalCard;
    private Map<String, BigDecimal> byType;

    public OperationSummaryDTO(LocalDate from, LocalDate to, int totalOperations,
                                BigDecimal totalIncome, BigDecimal totalExpense,
                                BigDecimal totalCash, BigDecimal totalCard,
                                Map<String, BigDecimal> byType) {
        this.from            = from;
        this.to              = to;
        this.totalOperations = totalOperations;
        this.totalIncome     = totalIncome;
        this.totalExpense    = totalExpense;
        this.netBalance      = totalIncome.subtract(totalExpense);
        this.totalCash       = totalCash;
        this.totalCard       = totalCard;
        this.byType          = byType;
    }

    public LocalDate getFrom() { return from; }
    public LocalDate getTo() { return to; }
    public int getTotalOperations() { return totalOperations; }
    public BigDecimal getTotalIncome() { return totalIncome; }
    public BigDecimal getTotalExpense() { return totalExpense; }
    public BigDecimal getNetBalance() { return netBalance; }
    public BigDecimal getTotalCash() { return totalCash; }
    public BigDecimal getTotalCard() { return totalCard; }
    public Map<String, BigDecimal> getByType() { return byType; }
}
