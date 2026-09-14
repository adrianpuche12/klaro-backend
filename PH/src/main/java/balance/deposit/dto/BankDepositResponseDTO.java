package balance.deposit.dto;

import balance.deposit.model.BankDeposit;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code expectedCash} y {@code difference} son calculados a partir de los
 * {@link balance.model.ClosingDeposit} vinculados -- no se persisten en
 * {@link BankDeposit}, para que siempre reflejen el estado real de los
 * cierres agrupados.
 */
@Getter
public class BankDepositResponseDTO {

    private final Long id;
    private final Long storeId;
    private final String storeName;
    private final LocalDate depositDate;
    private final BigDecimal declaredAmount;
    private final BigDecimal expectedCash;
    private final BigDecimal difference;
    private final String notes;
    private final String imageUri;
    private final String username;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final int closingsCount;
    private final List<Long> closingDepositIds;

    private BankDepositResponseDTO(Long id, Long storeId, String storeName, LocalDate depositDate,
                                    BigDecimal declaredAmount, BigDecimal expectedCash, BigDecimal difference,
                                    String notes, String imageUri, String username,
                                    LocalDateTime createdAt, LocalDateTime updatedAt,
                                    int closingsCount, List<Long> closingDepositIds) {
        this.id = id;
        this.storeId = storeId;
        this.storeName = storeName;
        this.depositDate = depositDate;
        this.declaredAmount = declaredAmount;
        this.expectedCash = expectedCash;
        this.difference = difference;
        this.notes = notes;
        this.imageUri = imageUri;
        this.username = username;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.closingsCount = closingsCount;
        this.closingDepositIds = closingDepositIds;
    }

    public static BankDepositResponseDTO of(BankDeposit deposit, BigDecimal expectedCash, List<Long> closingDepositIds) {
        BigDecimal declared = deposit.getDeclaredAmount();
        return new BankDepositResponseDTO(
                deposit.getId(),
                deposit.getStore() != null ? deposit.getStore().getId() : null,
                deposit.getStore() != null ? deposit.getStore().getName() : null,
                deposit.getDepositDate(),
                declared,
                expectedCash,
                declared.subtract(expectedCash),
                deposit.getNotes(),
                deposit.getImageUri(),
                deposit.getUsername(),
                deposit.getCreatedAt(),
                deposit.getUpdatedAt(),
                closingDepositIds.size(),
                closingDepositIds
        );
    }
}
