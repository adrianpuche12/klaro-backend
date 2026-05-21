package balance.repository;

import balance.model.SupplierPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {

    List<SupplierPayment> findByStoreIdAndTenantIdOrderByPaymentDateDesc(Long storeId, Long tenantId);

    List<SupplierPayment> findByTenantIdOrderByPaymentDateDesc(Long tenantId);

    @Query("SELECT s FROM SupplierPayment s WHERE s.tenantId = :tenantId " +
           "AND s.paymentDate BETWEEN :start AND :end ORDER BY s.paymentDate DESC")
    List<SupplierPayment> findByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT s FROM SupplierPayment s WHERE s.store.id = :storeId AND s.tenantId = :tenantId " +
           "AND s.paymentDate BETWEEN :start AND :end ORDER BY s.paymentDate DESC")
    List<SupplierPayment> findByStoreIdAndTenantIdAndDateRange(
            @Param("storeId") Long storeId,
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    List<SupplierPayment> findByUsernameAndTenantIdOrderByPaymentDateDesc(String username, Long tenantId);

    Optional<SupplierPayment> findByIdAndTenantId(Long id, Long tenantId);
}
