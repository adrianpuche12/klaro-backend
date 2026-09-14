package balance.deposit.repository;

import balance.deposit.model.BankDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BankDepositRepository extends JpaRepository<BankDeposit, Long> {

    List<BankDeposit> findByStoreIdAndTenantIdOrderByDepositDateDesc(Long storeId, Long tenantId);

    List<BankDeposit> findByTenantIdOrderByDepositDateDesc(Long tenantId);

    Optional<BankDeposit> findByIdAndTenantId(Long id, Long tenantId);

    // ── Panel unificado de operaciones (OperationsV3Service) ────────────────────

    @Query("SELECT b FROM BankDeposit b WHERE b.tenantId = :tenantId " +
           "AND b.depositDate BETWEEN :start AND :end ORDER BY b.depositDate DESC")
    List<BankDeposit> findByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT b FROM BankDeposit b WHERE b.store.id = :storeId AND b.tenantId = :tenantId " +
           "AND b.depositDate BETWEEN :start AND :end ORDER BY b.depositDate DESC")
    List<BankDeposit> findByStoreIdAndTenantIdAndDateRange(
            @Param("storeId") Long storeId, @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start, @Param("end") LocalDate end);
}
