package balance.repository;

import balance.model.SalaryPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalaryPaymentRepository extends JpaRepository<SalaryPayment, Long> {

    List<SalaryPayment> findByStoreIdAndTenantIdOrderBySalaryDateDesc(Long storeId, Long tenantId);

    List<SalaryPayment> findByTenantIdOrderBySalaryDateDesc(Long tenantId);

    @Query("SELECT s FROM SalaryPayment s WHERE s.tenantId = :tenantId " +
           "AND s.salaryDate BETWEEN :start AND :end ORDER BY s.salaryDate DESC")
    List<SalaryPayment> findByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT s FROM SalaryPayment s WHERE s.store.id = :storeId AND s.tenantId = :tenantId " +
           "AND s.salaryDate BETWEEN :start AND :end ORDER BY s.salaryDate DESC")
    List<SalaryPayment> findByStoreIdAndTenantIdAndDateRange(
            @Param("storeId") Long storeId,
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    List<SalaryPayment> findByUsernameAndTenantIdOrderBySalaryDateDesc(String username, Long tenantId);

    Optional<SalaryPayment> findByIdAndTenantId(Long id, Long tenantId);
}
