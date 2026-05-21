package balance.repository;

import balance.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByStoreIdAndTenantIdOrderByDateDesc(Long storeId, Long tenantId);

    List<Transaction> findByTenantIdOrderByDateDesc(Long tenantId);

    @Query("SELECT t FROM Transaction t WHERE t.tenantId = :tenantId " +
           "AND t.date BETWEEN :start AND :end ORDER BY t.date DESC")
    List<Transaction> findByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT t FROM Transaction t WHERE t.store.id = :storeId AND t.tenantId = :tenantId " +
           "AND t.date BETWEEN :start AND :end ORDER BY t.date DESC")
    List<Transaction> findByStoreIdAndTenantIdAndDateRange(
            @Param("storeId") Long storeId,
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    List<Transaction> findByTypeAndTenantIdOrderByDateDesc(String type, Long tenantId);

    List<Transaction> findByGastoAdminId(Long gastoAdminId);

    void deleteByGastoAdminId(Long gastoAdminId);

    Optional<Transaction> findByIdAndTenantId(Long id, Long tenantId);
}
