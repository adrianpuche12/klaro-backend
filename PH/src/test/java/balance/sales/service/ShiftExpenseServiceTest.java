package balance.sales.service;

import balance.common.enums.ShiftStatus;
import balance.model.Store;
import balance.sales.dto.ShiftExpenseRequestDTO;
import balance.sales.dto.ShiftExpenseResponseDTO;
import balance.sales.model.Shift;
import balance.sales.model.ShiftExpense;
import balance.sales.repository.ShiftExpenseRepository;
import balance.sales.repository.ShiftRepository;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPRINT-12 — egresos de caja durante un turno abierto. */
@ExtendWith(MockitoExtension.class)
class ShiftExpenseServiceTest {

    private static final Long TENANT_A = 1L;
    private static final Long TENANT_B = 2L;

    @InjectMocks private ShiftExpenseService shiftExpenseService;

    @Mock private ShiftExpenseRepository shiftExpenseRepository;
    @Mock private ShiftRepository        shiftRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_A); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    private Shift buildShift(Long id, ShiftStatus status, Long tenantId) {
        Shift shift = new Shift();
        shift.setId(id);
        Store store = new Store(); store.setId(1L); store.setName("Danli"); store.setTenantId(tenantId);
        shift.setStore(store);
        shift.setStatus(status);
        shift.setCode("T-20260514-0900-DAN");
        shift.setUsername("cajero01");
        shift.setTenantId(tenantId);
        return shift;
    }

    private ShiftExpenseRequestDTO buildRequest(String amount, String reason) {
        ShiftExpenseRequestDTO dto = new ShiftExpenseRequestDTO();
        dto.setAmount(new BigDecimal(amount));
        dto.setReason(reason);
        dto.setUsername("cajero01");
        return dto;
    }

    // ── addExpense ───────────────────────────────────────────────────────────

    @Test
    void addExpense_savesExpenseForOpenShift() {
        Shift shift = buildShift(1L, ShiftStatus.OPEN, TENANT_A);
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(shift));
        when(shiftExpenseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShiftExpenseResponseDTO result = shiftExpenseService.addExpense(1L, buildRequest("20.00", "Pago repartidor"));

        assertThat(result.getAmount()).isEqualByComparingTo("20.00");
        assertThat(result.getReason()).isEqualTo("Pago repartidor");
    }

    @Test
    void addExpense_throwsWhenShiftClosed() {
        Shift shift = buildShift(1L, ShiftStatus.CLOSED, TENANT_A);
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(shift));

        assertThatThrownBy(() -> shiftExpenseService.addExpense(1L, buildRequest("20.00", "Pago repartidor")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turno cerrado");

        verify(shiftExpenseRepository, never()).save(any());
    }

    @Test
    void addExpense_throwsWhenShiftNotFound() {
        when(shiftRepository.findByIdAndTenantId(99L, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftExpenseService.addExpense(99L, buildRequest("20.00", "Pago repartidor")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Turno no encontrado");
    }

    @Test
    void addExpense_tenantBCannotAddExpenseToTenantAShift() {
        TenantContext.setTenantId(TENANT_B);
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_B)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftExpenseService.addExpense(1L, buildRequest("20.00", "Pago repartidor")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(shiftExpenseRepository, never()).save(any());
    }

    // ── getExpensesForShift / getTotalExpenses ──────────────────────────────────

    @Test
    void getExpensesForShift_returnsExpensesForTenant() {
        ShiftExpense e = new ShiftExpense();
        e.setId(1L);
        e.setAmount(new BigDecimal("15.00"));
        e.setReason("Cambio faltante");
        when(shiftExpenseRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(1L, TENANT_A))
                .thenReturn(List.of(e));

        List<ShiftExpenseResponseDTO> result = shiftExpenseService.getExpensesForShift(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReason()).isEqualTo("Cambio faltante");
    }

    @Test
    void getTotalExpenses_sumsAllExpensesForShift() {
        ShiftExpense e1 = new ShiftExpense(); e1.setAmount(new BigDecimal("10.00"));
        ShiftExpense e2 = new ShiftExpense(); e2.setAmount(new BigDecimal("15.50"));
        when(shiftExpenseRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(1L, TENANT_A))
                .thenReturn(List.of(e1, e2));

        BigDecimal total = shiftExpenseService.getTotalExpenses(1L, TENANT_A);

        assertThat(total).isEqualByComparingTo("25.50");
    }

    @Test
    void getTotalExpenses_returnsZero_whenNoExpenses() {
        when(shiftExpenseRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(1L, TENANT_A))
                .thenReturn(List.of());

        assertThat(shiftExpenseService.getTotalExpenses(1L, TENANT_A)).isEqualByComparingTo("0");
    }
}
