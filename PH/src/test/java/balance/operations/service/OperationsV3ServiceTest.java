package balance.operations.service;

import balance.common.enums.SaleStatus;
import balance.model.ClosingDeposit;
import balance.model.GastoAdmin;
import balance.model.SalaryPayment;
import balance.model.Store;
import balance.model.SupplierPayment;
import balance.model.Transaction;
import balance.operations.dto.OperationDTO;
import balance.operations.dto.OperationSummaryDTO;
import balance.repository.ClosingDepositRepository;
import balance.repository.GastoAdminRepository;
import balance.repository.SalaryPaymentRepository;
import balance.repository.StoreRepository;
import balance.repository.SupplierPaymentRepository;
import balance.repository.TransactionRepository;
import balance.sales.model.Sale;
import balance.sales.repository.SaleRepository;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OperationsV3ServiceTest {

    private static final Long      TENANT_ID = 1L;
    private static final Long      STORE_ID  = 1L;
    private static final LocalDate FROM      = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO        = LocalDate.of(2026, 12, 31);

    @InjectMocks private OperationsV3Service operationsService;

    @Mock private ClosingDepositRepository  closingDepositRepository;
    @Mock private SaleRepository            saleRepository;
    @Mock private SupplierPaymentRepository supplierPaymentRepository;
    @Mock private SalaryPaymentRepository   salaryPaymentRepository;
    @Mock private GastoAdminRepository      gastoAdminRepository;
    @Mock private TransactionRepository     transactionRepository;
    @Mock private StoreRepository           storeRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id) {
        Store s = new Store(); s.setId(id); s.setName("Local Central"); s.setTenantId(TENANT_ID); return s;
    }

    private Sale buildSale(Long id, BigDecimal total) {
        Sale s = new Sale();
        s.setStatus(SaleStatus.OPEN);
        s.setTotal(total);
        s.setSubtotal(total);
        s.setIsv(BigDecimal.ZERO);
        s.setUsername("cajero01");
        s.setSaleDate(LocalDate.of(2026, 5, 21));
        s.setStore(buildStore(STORE_ID));
        s.setTenantId(TENANT_ID);
        return s;
    }

    private void stubEmptyRepos() {
        lenient().when(closingDepositRepository.findByTenantIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        lenient().when(supplierPaymentRepository.findByTenantIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        lenient().when(salaryPaymentRepository.findByTenantIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        lenient().when(gastoAdminRepository.findByTenantIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        lenient().when(transactionRepository.findByTenantIdAndDateRange(any(), any(), any())).thenReturn(List.of());
        lenient().when(storeRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(buildStore(STORE_ID)));
        lenient().when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of());
    }

    // ── getOperations — sin filtros ───────────────────────────────────────────

    @Test
    void getOperations_returnsEmpty_whenNoData() {
        stubEmptyRepos();

        List<OperationDTO> result = operationsService.getOperations(FROM, TO, null, null, "DATE_DESC", 0, 20);

        assertThat(result).isEmpty();
    }

    @Test
    void getOperations_returnsSales_whenSalesExist() {
        stubEmptyRepos();
        Sale sale = buildSale(1L, new BigDecimal("300.00"));
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of(sale));

        List<OperationDTO> result = operationsService.getOperations(FROM, TO, null, null, "DATE_DESC", 0, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("SALE");
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("300.00");
    }

    // ── getOperations — filtro por tipo ───────────────────────────────────────

    @Test
    void getOperations_filterBySaleType_doesNotQueryOtherRepos() {
        Sale sale = buildSale(1L, new BigDecimal("200.00"));
        when(storeRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(buildStore(STORE_ID)));
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of(sale));

        List<OperationDTO> result = operationsService.getOperations(FROM, TO, "SALE", null, "DATE_DESC", 0, 20);

        assertThat(result).hasSize(1);
        verify(closingDepositRepository, never()).findByTenantIdAndDateRange(any(), any(), any());
        verify(gastoAdminRepository,     never()).findByTenantIdAndDateRange(any(), any(), any());
    }

    @Test
    void getOperations_filterByClosingType_doesNotQuerySales() {
        when(closingDepositRepository.findByTenantIdAndDateRange(eq(TENANT_ID), any(), any()))
                .thenReturn(List.of());

        List<OperationDTO> result = operationsService.getOperations(FROM, TO, "CLOSING", null, "DATE_DESC", 0, 20);

        assertThat(result).isEmpty();
        verify(saleRepository, never()).findByStoreIdAndTenantIdAndDateRangeStrict(any(), any(), any(), any());
    }

    // ── getOperations — filtro por storeId ────────────────────────────────────

    @Test
    void getOperations_withStoreId_validatesStoreOwnership() {
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                operationsService.getOperations(FROM, TO, null, 99L, "DATE_DESC", 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local no encontrado");
    }

    @Test
    void getOperations_withStoreId_queriesSpecificStore() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(buildStore(STORE_ID)));
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(eq(STORE_ID), eq(TENANT_ID), any(), any()))
                .thenReturn(List.of());
        when(closingDepositRepository.findByStoreIdAndTenantIdAndDateRange(eq(STORE_ID), eq(TENANT_ID), any(), any()))
                .thenReturn(List.of());
        when(supplierPaymentRepository.findByStoreIdAndTenantIdAndDateRange(eq(STORE_ID), eq(TENANT_ID), any(), any()))
                .thenReturn(List.of());
        when(salaryPaymentRepository.findByStoreIdAndTenantIdAndDateRange(eq(STORE_ID), eq(TENANT_ID), any(), any()))
                .thenReturn(List.of());
        when(transactionRepository.findByStoreIdAndTenantIdAndDateRange(eq(STORE_ID), eq(TENANT_ID), any(), any()))
                .thenReturn(List.of());
        when(gastoAdminRepository.findByTenantIdAndDateRange(eq(TENANT_ID), any(), any()))
                .thenReturn(List.of());

        List<OperationDTO> result = operationsService.getOperations(FROM, TO, null, STORE_ID, "DATE_DESC", 0, 20);

        assertThat(result).isEmpty();
        verify(storeRepository, never()).findByTenantId(any()); // usa store-specific, no findAll
    }

    // ── getOperations — paginación ────────────────────────────────────────────

    @Test
    void getOperations_paginationLimitsResults() {
        stubEmptyRepos();
        Sale s1 = buildSale(1L, new BigDecimal("100.00"));
        Sale s2 = buildSale(2L, new BigDecimal("200.00"));
        Sale s3 = buildSale(3L, new BigDecimal("300.00"));
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of(s1, s2, s3));

        List<OperationDTO> result = operationsService.getOperations(FROM, TO, "SALE", null, "DATE_DESC", 0, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void getOperations_paginationPage2() {
        stubEmptyRepos();
        Sale s1 = buildSale(1L, new BigDecimal("100.00"));
        Sale s2 = buildSale(2L, new BigDecimal("200.00"));
        Sale s3 = buildSale(3L, new BigDecimal("300.00"));
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of(s1, s2, s3));

        List<OperationDTO> result = operationsService.getOperations(FROM, TO, "SALE", null, "DATE_DESC", 1, 2);

        assertThat(result).hasSize(1); // página 2 con size=2 → solo 1 elemento queda
    }

    @Test
    void getOperations_beyondLastPage_returnsEmpty() {
        stubEmptyRepos();
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of(buildSale(1L, BigDecimal.TEN)));

        List<OperationDTO> result = operationsService.getOperations(FROM, TO, "SALE", null, "DATE_DESC", 5, 10);

        assertThat(result).isEmpty();
    }

    // ── getSummary ────────────────────────────────────────────────────────────

    @Test
    void getSummary_calculatesIncomeFromSales() {
        stubEmptyRepos();
        Sale s1 = buildSale(1L, new BigDecimal("300.00"));
        Sale s2 = buildSale(2L, new BigDecimal("200.00"));
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of(s1, s2));

        OperationSummaryDTO summary = operationsService.getSummary(FROM, TO, null);

        assertThat(summary.getTotalIncome()).isEqualByComparingTo("500.00");
        assertThat(summary.getTotalExpense()).isEqualByComparingTo("0.00");
        assertThat(summary.getNetBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void getSummary_countsAllOperations() {
        stubEmptyRepos();
        Sale sale = buildSale(1L, new BigDecimal("100.00"));
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of(sale));

        OperationSummaryDTO summary = operationsService.getSummary(FROM, TO, null);

        assertThat(summary.getTotalOperations()).isEqualTo(1);
    }
}
