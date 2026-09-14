package balance.deposit.service;

import balance.deposit.dto.BankDepositRequestDTO;
import balance.deposit.dto.BankDepositResponseDTO;
import balance.deposit.dto.BankDepositUpdateDTO;
import balance.deposit.dto.PendingClosingDTO;
import balance.deposit.model.BankDeposit;
import balance.deposit.repository.BankDepositRepository;
import balance.model.ClosingDeposit;
import balance.model.Store;
import balance.repository.ClosingDepositRepository;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPRINT-11 — BankDepositService: agrupar cierres pendientes en un depósito,
 * editar, eliminar en cascada, y aislamiento por tenant (mismo patrón que
 * balance.security.TenantIsolationTest).
 */
@ExtendWith(MockitoExtension.class)
class BankDepositServiceTest {

    private static final Long TENANT_A = 1L;
    private static final Long TENANT_B = 2L;
    private static final Long STORE_ID = 10L;

    @InjectMocks private BankDepositService bankDepositService;

    @Mock private BankDepositRepository    bankDepositRepository;
    @Mock private ClosingDepositRepository closingDepositRepository;
    @Mock private StoreRepository          storeRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void setTenant() {
        TenantContext.setTenantId(TENANT_A);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Store buildStore(Long id, Long tenantId) {
        Store s = new Store();
        s.setId(id);
        s.setName("Local Central");
        s.setTenantId(tenantId);
        return s;
    }

    private ClosingDeposit buildClosing(Long id, BigDecimal amount) {
        ClosingDeposit c = new ClosingDeposit();
        c.setId(id);
        c.setTenantId(TENANT_A);
        c.setAmount(amount);
        c.setDepositDate(LocalDate.of(2026, 5, 21));
        c.setPeriodStart(LocalDate.of(2026, 5, 1));
        c.setPeriodEnd(LocalDate.of(2026, 5, 21));
        c.setClosingsCount(1);
        c.setUsername("gimena_alba");
        c.setStore(buildStore(STORE_ID, TENANT_A));
        return c;
    }

    private BankDeposit buildDeposit(Long id, Long tenantId, BigDecimal declaredAmount) {
        BankDeposit b = new BankDeposit();
        b.setId(id);
        b.setTenantId(tenantId);
        b.setStore(buildStore(STORE_ID, tenantId));
        b.setDepositDate(LocalDate.of(2026, 5, 21));
        b.setDeclaredAmount(declaredAmount);
        b.setUsername("gimena_alba");
        return b;
    }

    private BankDepositRequestDTO buildRequest(BigDecimal declaredAmount) {
        BankDepositRequestDTO dto = new BankDepositRequestDTO();
        dto.setStoreId(STORE_ID);
        dto.setPeriodStart(LocalDate.of(2026, 5, 1));
        dto.setPeriodEnd(LocalDate.of(2026, 5, 21));
        dto.setDeclaredAmount(declaredAmount);
        dto.setUsername("gimena_alba");
        return dto;
    }

    // ── findPendingClosings ──────────────────────────────────────────────────

    @Test
    void findPendingClosings_returnsClosingsWithoutBankDeposit() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_A))
                .thenReturn(Optional.of(buildStore(STORE_ID, TENANT_A)));
        ClosingDeposit c = buildClosing(1L, new BigDecimal("850.00"));
        when(closingDepositRepository.findPendingByStoreIdAndTenantIdAndDateRange(
                eq(STORE_ID), eq(TENANT_A), any(), any())).thenReturn(List.of(c));

        List<PendingClosingDTO> result = bankDepositService.findPendingClosings(
                STORE_ID, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 21));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("850.00");
    }

    @Test
    void findPendingClosings_rejectsForeignStore() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bankDepositService.findPendingClosings(
                STORE_ID, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 21)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local no encontrado");
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void create_groupsPendingClosingsAndComputesExpectedCash() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_A))
                .thenReturn(Optional.of(buildStore(STORE_ID, TENANT_A)));
        ClosingDeposit c1 = buildClosing(1L, new BigDecimal("850.00"));
        ClosingDeposit c2 = buildClosing(2L, new BigDecimal("920.00"));
        when(closingDepositRepository.findPendingByStoreIdAndTenantIdAndDateRange(
                eq(STORE_ID), eq(TENANT_A), any(), any())).thenReturn(List.of(c1, c2));
        when(bankDepositRepository.save(any())).thenAnswer(inv -> {
            BankDeposit b = inv.getArgument(0);
            b.setId(100L);
            return b;
        });

        BankDepositResponseDTO result = bankDepositService.create(buildRequest(new BigDecimal("1770.00")));

        assertThat(result.getExpectedCash()).isEqualByComparingTo("1770.00");
        assertThat(result.getDeclaredAmount()).isEqualByComparingTo("1770.00");
        assertThat(result.getDifference()).isEqualByComparingTo("0.00");
        assertThat(result.getClosingsCount()).isEqualTo(2);

        // Los dos cierres quedan vinculados al depósito creado
        assertThat(c1.getBankDeposit()).isNotNull();
        assertThat(c2.getBankDeposit()).isNotNull();
        verify(closingDepositRepository).saveAll(List.of(c1, c2));
    }

    @Test
    void create_computesPositiveDifference_whenDeclaredExceedsExpected() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_A))
                .thenReturn(Optional.of(buildStore(STORE_ID, TENANT_A)));
        when(closingDepositRepository.findPendingByStoreIdAndTenantIdAndDateRange(
                eq(STORE_ID), eq(TENANT_A), any(), any())).thenReturn(List.of(buildClosing(1L, new BigDecimal("800.00"))));
        when(bankDepositRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BankDepositResponseDTO result = bankDepositService.create(buildRequest(new BigDecimal("850.00")));

        assertThat(result.getDifference()).isEqualByComparingTo("50.00");
    }

    @Test
    void create_rejectsWhenNoPendingClosingsInPeriod() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_A))
                .thenReturn(Optional.of(buildStore(STORE_ID, TENANT_A)));
        when(closingDepositRepository.findPendingByStoreIdAndTenantIdAndDateRange(
                eq(STORE_ID), eq(TENANT_A), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> bankDepositService.create(buildRequest(new BigDecimal("100.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No hay cierres pendientes");

        verify(bankDepositRepository, never()).save(any());
    }

    @Test
    void create_rejectsForeignStore() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bankDepositService.create(buildRequest(new BigDecimal("100.00"))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(bankDepositRepository, never()).save(any());
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    void update_propagatesDateAndImageToLinkedClosings() {
        BankDeposit deposit = buildDeposit(5L, TENANT_A, new BigDecimal("1000.00"));
        when(bankDepositRepository.findByIdAndTenantId(5L, TENANT_A)).thenReturn(Optional.of(deposit));
        when(bankDepositRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(closingDepositRepository.findByBankDepositIdAndTenantId(5L, TENANT_A)).thenReturn(List.of());

        BankDepositUpdateDTO dto = new BankDepositUpdateDTO();
        dto.setDepositDate(LocalDate.of(2026, 5, 25));
        dto.setImageUri("https://r2/comprobante.jpg");
        dto.setDeclaredAmount(new BigDecimal("1050.00"));

        BankDepositResponseDTO result = bankDepositService.update(5L, dto);

        assertThat(result.getDeclaredAmount()).isEqualByComparingTo("1050.00");
        verify(closingDepositRepository).updateDepositDateByBankDepositId(5L, LocalDate.of(2026, 5, 25));
        verify(closingDepositRepository).updateImageUriByBankDepositId(5L, "https://r2/comprobante.jpg");
    }

    @Test
    void update_doesNotPropagate_whenDateAndImageNotProvided() {
        BankDeposit deposit = buildDeposit(5L, TENANT_A, new BigDecimal("1000.00"));
        when(bankDepositRepository.findByIdAndTenantId(5L, TENANT_A)).thenReturn(Optional.of(deposit));
        when(bankDepositRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(closingDepositRepository.findByBankDepositIdAndTenantId(5L, TENANT_A)).thenReturn(List.of());

        BankDepositUpdateDTO dto = new BankDepositUpdateDTO();
        dto.setNotes("ajuste de notas");

        bankDepositService.update(5L, dto);

        verify(closingDepositRepository, never()).updateDepositDateByBankDepositId(anyLong(), any());
        verify(closingDepositRepository, never()).updateImageUriByBankDepositId(anyLong(), any());
    }

    @Test
    void update_tenantBCannotUpdateTenantADeposit() {
        TenantContext.setTenantId(TENANT_B);
        when(bankDepositRepository.findByIdAndTenantId(5L, TENANT_B)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bankDepositService.update(5L, new BankDepositUpdateDTO()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Depósito no encontrado");

        verify(bankDepositRepository, never()).save(any());
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void delete_cascadesToLinkedClosings() {
        BankDeposit deposit = buildDeposit(5L, TENANT_A, new BigDecimal("1000.00"));
        when(bankDepositRepository.findByIdAndTenantId(5L, TENANT_A)).thenReturn(Optional.of(deposit));
        List<ClosingDeposit> linked = List.of(buildClosing(1L, new BigDecimal("500.00")), buildClosing(2L, new BigDecimal("500.00")));
        when(closingDepositRepository.findByBankDepositIdAndTenantId(5L, TENANT_A)).thenReturn(linked);

        bankDepositService.delete(5L);

        verify(closingDepositRepository).deleteAll(linked);
        verify(bankDepositRepository).delete(deposit);
    }

    @Test
    void delete_tenantBCannotDeleteTenantADeposit() {
        TenantContext.setTenantId(TENANT_B);
        when(bankDepositRepository.findByIdAndTenantId(5L, TENANT_B)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bankDepositService.delete(5L))
                .isInstanceOf(IllegalArgumentException.class);

        verify(bankDepositRepository, never()).delete(any());
        verify(closingDepositRepository, never()).deleteAll(any());
    }

    // ── findById / findAll ───────────────────────────────────────────────────

    @Test
    void findById_tenantBCannotAccessTenantADeposit() {
        TenantContext.setTenantId(TENANT_B);
        when(bankDepositRepository.findByIdAndTenantId(5L, TENANT_B)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bankDepositService.findById(5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findAll_withoutStoreId_queriesAllTenantDeposits() {
        when(bankDepositRepository.findByTenantIdOrderByDepositDateDesc(TENANT_A)).thenReturn(List.of());

        bankDepositService.findAll(null);

        verify(bankDepositRepository).findByTenantIdOrderByDepositDateDesc(TENANT_A);
        verify(bankDepositRepository, never()).findByStoreIdAndTenantIdOrderByDepositDateDesc(any(), any());
    }

    @Test
    void findAll_withStoreId_queriesSpecificStore() {
        when(bankDepositRepository.findByStoreIdAndTenantIdOrderByDepositDateDesc(STORE_ID, TENANT_A))
                .thenReturn(List.of());

        bankDepositService.findAll(STORE_ID);

        verify(bankDepositRepository).findByStoreIdAndTenantIdOrderByDepositDateDesc(STORE_ID, TENANT_A);
        verify(bankDepositRepository, never()).findByTenantIdOrderByDepositDateDesc(any());
    }

    @Test
    void requireTenantId_throwsSecurityException_whenNoContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> bankDepositService.findAll(null))
                .isInstanceOf(SecurityException.class);
    }
}
