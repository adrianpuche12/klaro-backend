package balance.repository;

import balance.model.ClosingDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClosingDepositRepository extends JpaRepository<ClosingDeposit, Long> {

    List<ClosingDeposit> findByStoreIdAndTenantIdOrderByDepositDateDesc(Long storeId, Long tenantId);

    List<ClosingDeposit> findByTenantIdOrderByDepositDateDesc(Long tenantId);

    @Query("SELECT c FROM ClosingDeposit c WHERE c.tenantId = :tenantId " +
           "AND c.depositDate BETWEEN :start AND :end ORDER BY c.depositDate DESC")
    List<ClosingDeposit> findByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT c FROM ClosingDeposit c WHERE c.store.id = :storeId AND c.tenantId = :tenantId " +
           "AND c.depositDate BETWEEN :start AND :end ORDER BY c.depositDate DESC")
    List<ClosingDeposit> findByStoreIdAndTenantIdAndDateRange(
            @Param("storeId") Long storeId,
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    List<ClosingDeposit> findByUsernameAndTenantIdOrderByDepositDateDesc(String username, Long tenantId);

    Optional<ClosingDeposit> findByIdAndTenantId(Long id, Long tenantId);

    // ── SPRINT-11: agrupación en BankDeposit ────────────────────────────────────

    /** Cierres del local todavía sin agrupar en un depósito, en el rango dado. */
    @Query("SELECT c FROM ClosingDeposit c WHERE c.store.id = :storeId AND c.tenantId = :tenantId " +
           "AND c.bankDeposit IS NULL AND c.depositDate BETWEEN :start AND :end " +
           "ORDER BY c.depositDate DESC")
    List<ClosingDeposit> findPendingByStoreIdAndTenantIdAndDateRange(
            @Param("storeId") Long storeId,
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    List<ClosingDeposit> findByBankDepositIdAndTenantId(Long bankDepositId, Long tenantId);

    @Modifying
    @Query("UPDATE ClosingDeposit c SET c.depositDate = :date WHERE c.bankDeposit.id = :bankDepositId")
    void updateDepositDateByBankDepositId(@Param("bankDepositId") Long bankDepositId, @Param("date") LocalDate date);

    @Modifying
    @Query("UPDATE ClosingDeposit c SET c.imageUri = :imageUri WHERE c.bankDeposit.id = :bankDepositId")
    void updateImageUriByBankDepositId(@Param("bankDepositId") Long bankDepositId, @Param("imageUri") String imageUri);
}
