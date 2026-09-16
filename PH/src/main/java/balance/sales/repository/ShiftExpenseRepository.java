package balance.sales.repository;

import balance.sales.model.ShiftExpense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShiftExpenseRepository extends JpaRepository<ShiftExpense, Long> {

    List<ShiftExpense> findByShiftIdAndTenantIdOrderByCreatedAtDesc(Long shiftId, Long tenantId);
}
