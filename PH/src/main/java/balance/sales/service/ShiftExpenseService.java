package balance.sales.service;

import balance.common.enums.ShiftStatus;
import balance.sales.dto.ShiftExpenseRequestDTO;
import balance.sales.dto.ShiftExpenseResponseDTO;
import balance.sales.model.Shift;
import balance.sales.model.ShiftExpense;
import balance.sales.repository.ShiftExpenseRepository;
import balance.sales.repository.ShiftRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Egresos de caja durante un turno abierto -- SPRINT-12. */
@Service
public class ShiftExpenseService {

    @Autowired private ShiftExpenseRepository shiftExpenseRepository;
    @Autowired private ShiftRepository        shiftRepository;

    @Transactional
    public ShiftExpenseResponseDTO addExpense(Long shiftId, ShiftExpenseRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Turno no encontrado"));
        if (ShiftStatus.CLOSED == shift.getStatus()) {
            throw new IllegalStateException("No se pueden registrar egresos en un turno cerrado");
        }

        ShiftExpense expense = new ShiftExpense();
        expense.setTenantId(tenantId);
        expense.setShift(shift);
        expense.setAmount(dto.getAmount());
        expense.setReason(dto.getReason());
        expense.setUsername(dto.getUsername());

        return ShiftExpenseResponseDTO.from(shiftExpenseRepository.save(expense));
    }

    public List<ShiftExpenseResponseDTO> getExpensesForShift(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return shiftExpenseRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(shiftId, tenantId)
                .stream().map(ShiftExpenseResponseDTO::from).toList();
    }

    /** Suma de egresos del turno -- usada internamente para la reconciliación de caja. */
    BigDecimal getTotalExpenses(Long shiftId, Long tenantId) {
        return shiftExpenseRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(shiftId, tenantId)
                .stream().map(ShiftExpense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
