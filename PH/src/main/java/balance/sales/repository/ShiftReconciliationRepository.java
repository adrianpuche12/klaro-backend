package balance.sales.repository;

import balance.sales.model.ShiftReconciliation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShiftReconciliationRepository extends JpaRepository<ShiftReconciliation, Long> {

    Optional<ShiftReconciliation> findByShiftIdAndTenantId(Long shiftId, Long tenantId);
}
