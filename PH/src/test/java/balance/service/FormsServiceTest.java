package balance.service;

import balance.dto.GastoAdminRequestDTO;
import balance.dto.GastoAdminResponseDTO;
import balance.model.*;
import balance.repository.*;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FormsServiceTest {

    private static final Long TENANT_ID = 1L;

    @InjectMocks private FormsService formsService;

    @Mock private GastoAdminRepository      gastoAdminRepository;
    @Mock private TransactionRepository      transactionRepository;
    @Mock private StoreRepository            storeRepository;
    @Mock private ClosingDepositRepository   closingDepositRepository;
    @Mock private SupplierPaymentRepository  supplierPaymentRepository;
    @Mock private SalaryPaymentRepository    salaryPaymentRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    private Store buildStore(Long id) {
        Store s = new Store(); s.setId(id); s.setName("Danli"); s.setTenantId(TENANT_ID); return s;
    }

    private GastoAdminRequestDTO buildGastoRequest(int pct1, int pct2) {
        GastoAdminRequestDTO r = new GastoAdminRequestDTO();
        r.setFecha(LocalDate.of(2026, 5, 1));
        r.setMonto(new BigDecimal("1000.00"));
        r.setDescripcion("Luz");
        r.setTipo("expense");
        r.setDistribuciones(List.of(
                new GastoAdminRequestDTO.StoreDistribucion(1L, pct1),
                new GastoAdminRequestDTO.StoreDistribucion(2L, pct2)));
        return r;
    }

    // ── saveClosingDeposit ────────────────────────────────────────────────────

    @Test
    void saveClosingDeposit_setsDepositDateWhenNull() {
        ClosingDeposit deposit = new ClosingDeposit();
        deposit.setAmount(new BigDecimal("500.00")); deposit.setTenantId(TENANT_ID);
        when(closingDepositRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClosingDeposit result = formsService.saveClosingDeposit(deposit);

        assertThat(result.getDepositDate()).isNotNull();
    }

    @Test
    void saveClosingDeposit_preservesExistingDate() {
        LocalDate fixed = LocalDate.of(2026, 1, 15);
        ClosingDeposit deposit = new ClosingDeposit();
        deposit.setAmount(new BigDecimal("500.00")); deposit.setDepositDate(fixed); deposit.setTenantId(TENANT_ID);
        when(closingDepositRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClosingDeposit result = formsService.saveClosingDeposit(deposit);

        assertThat(result.getDepositDate()).isEqualTo(fixed);
    }

    // ── saveSupplierPayment ───────────────────────────────────────────────────

    @Test
    void saveSupplierPayment_setsTenantId() {
        SupplierPayment p = new SupplierPayment();
        p.setAmount(new BigDecimal("200.00")); p.setUsername("admin"); p.setPaymentDate(LocalDate.now());
        when(supplierPaymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(formsService.saveSupplierPayment(p).getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void saveSupplierPayment_setsPaymentDateWhenNull() {
        SupplierPayment p = new SupplierPayment();
        p.setAmount(new BigDecimal("200.00")); p.setUsername("admin");
        when(supplierPaymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(formsService.saveSupplierPayment(p).getPaymentDate()).isNotNull();
    }

    // ── saveSalaryPayment ─────────────────────────────────────────────────────

    @Test
    void saveSalaryPayment_setsTenantId() {
        SalaryPayment p = new SalaryPayment();
        p.setAmount(new BigDecimal("3000.00")); p.setDescription("Mayo"); p.setUsername("admin"); p.setSalaryDate(LocalDate.now());
        when(salaryPaymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(formsService.saveSalaryPayment(p).getTenantId()).isEqualTo(TENANT_ID);
    }

    // ── saveGastoAdmin ────────────────────────────────────────────────────────

    @Test
    void saveGastoAdmin_throwsWhenPercentagesDontSum100() {
        assertThatThrownBy(() -> formsService.saveGastoAdmin(buildGastoRequest(60, 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100%");
    }

    @Test
    void saveGastoAdmin_createsOneTransactionPerDistribucion() {
        GastoAdminRequestDTO request = buildGastoRequest(60, 40);
        GastoAdmin saved = new GastoAdmin(); saved.setId(1L); saved.setTenantId(TENANT_ID); saved.setMonto(request.getMonto());

        when(gastoAdminRepository.save(any())).thenReturn(saved);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L)));
        when(storeRepository.findByIdAndTenantId(2L, TENANT_ID)).thenReturn(Optional.of(buildStore(2L)));
        when(transactionRepository.save(any())).thenAnswer(inv -> { Transaction tx = inv.getArgument(0); tx.setId(System.nanoTime()); return tx; });

        GastoAdminResponseDTO result = formsService.saveGastoAdmin(request);

        assertThat(result.getTransacciones()).hasSize(2);
        verify(transactionRepository, times(2)).save(any(Transaction.class));
    }

    @Test
    void saveGastoAdmin_distributesMountsByPercentage() {
        GastoAdminRequestDTO request = buildGastoRequest(60, 40);
        GastoAdmin saved = new GastoAdmin(); saved.setId(1L); saved.setTenantId(TENANT_ID); saved.setMonto(new BigDecimal("1000.00"));

        when(gastoAdminRepository.save(any())).thenReturn(saved);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L)));
        when(storeRepository.findByIdAndTenantId(2L, TENANT_ID)).thenReturn(Optional.of(buildStore(2L)));
        when(transactionRepository.save(any())).thenAnswer(inv -> { Transaction tx = inv.getArgument(0); tx.setId(System.nanoTime()); return tx; });

        GastoAdminResponseDTO result = formsService.saveGastoAdmin(request);

        BigDecimal total = result.getTransacciones().stream()
                .map(GastoAdminResponseDTO.TransaccionCreada::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("1000.00");
    }

    // ── updateGastoAdminV2 ────────────────────────────────────────────────────

    @Test
    void updateGastoAdminV2_throwsWhenPercentagesDontSum100() {
        assertThatThrownBy(() -> formsService.updateGastoAdminV2(1L, buildGastoRequest(50, 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100%");
    }

    @Test
    void updateGastoAdminV2_deletesOldTransactionsBeforeCreatingNew() {
        GastoAdminRequestDTO request = buildGastoRequest(70, 30);
        GastoAdmin existing = new GastoAdmin(); existing.setId(1L); existing.setTenantId(TENANT_ID); existing.setMonto(request.getMonto());

        when(gastoAdminRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(existing));
        when(gastoAdminRepository.save(any())).thenReturn(existing);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L)));
        when(storeRepository.findByIdAndTenantId(2L, TENANT_ID)).thenReturn(Optional.of(buildStore(2L)));
        when(transactionRepository.save(any())).thenAnswer(inv -> { Transaction tx = inv.getArgument(0); tx.setId(System.nanoTime()); return tx; });

        formsService.updateGastoAdminV2(1L, request);

        InOrder order = inOrder(transactionRepository);
        order.verify(transactionRepository).deleteByGastoAdminId(1L);
        order.verify(transactionRepository, times(2)).save(any(Transaction.class));
    }

    @Test
    void updateGastoAdminV2_throwsWhenGastoAdminNotFound() {
        when(gastoAdminRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> formsService.updateGastoAdminV2(99L, buildGastoRequest(60, 40)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    // ── getAllOperations — buildSortedOperations ───────────────────────────────

    @Test
    void getAllOperations_combinesAllThreeTypesAndSortsByDateDesc() {
        ClosingDeposit cd = new ClosingDeposit();
        cd.setAmount(new BigDecimal("100")); cd.setDepositDate(LocalDate.of(2026, 5, 1));
        cd.setTenantId(TENANT_ID); cd.setUsername("admin");

        SupplierPayment sp = new SupplierPayment();
        sp.setAmount(new BigDecimal("200")); sp.setPaymentDate(LocalDate.of(2026, 5, 3));
        sp.setTenantId(TENANT_ID); sp.setUsername("admin");

        SalaryPayment sal = new SalaryPayment();
        sal.setAmount(new BigDecimal("300")); sal.setSalaryDate(LocalDate.of(2026, 5, 2));
        sal.setTenantId(TENANT_ID); sal.setUsername("admin"); sal.setDescription("Sal");

        when(closingDepositRepository.findByTenantIdOrderByDepositDateDesc(TENANT_ID)).thenReturn(List.of(cd));
        when(supplierPaymentRepository.findByTenantIdOrderByPaymentDateDesc(TENANT_ID)).thenReturn(List.of(sp));
        when(salaryPaymentRepository.findByTenantIdOrderBySalaryDateDesc(TENANT_ID)).thenReturn(List.of(sal));

        var result = formsService.getAllOperations();

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getDate()).isEqualTo(LocalDate.of(2026, 5, 3));
        assertThat(result.get(2).getDate()).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void getAllOperations_returnsEmptyWhenNoData() {
        when(closingDepositRepository.findByTenantIdOrderByDepositDateDesc(TENANT_ID)).thenReturn(List.of());
        when(supplierPaymentRepository.findByTenantIdOrderByPaymentDateDesc(TENANT_ID)).thenReturn(List.of());
        when(salaryPaymentRepository.findByTenantIdOrderBySalaryDateDesc(TENANT_ID)).thenReturn(List.of());

        assertThat(formsService.getAllOperations()).isEmpty();
    }
}