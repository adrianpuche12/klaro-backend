package balance.deposit.dto;

import balance.model.ClosingDeposit;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Un cierre de caja todavía no agrupado en ningún depósito. */
@Getter
public class PendingClosingDTO {

    private final Long id;
    private final LocalDate depositDate;
    private final BigDecimal amount;
    private final Integer closingsCount;
    private final String username;

    private PendingClosingDTO(Long id, LocalDate depositDate, BigDecimal amount,
                               Integer closingsCount, String username) {
        this.id = id;
        this.depositDate = depositDate;
        this.amount = amount;
        this.closingsCount = closingsCount;
        this.username = username;
    }

    public static PendingClosingDTO from(ClosingDeposit c) {
        return new PendingClosingDTO(c.getId(), c.getDepositDate(), c.getAmount(),
                c.getClosingsCount(), c.getUsername());
    }
}
