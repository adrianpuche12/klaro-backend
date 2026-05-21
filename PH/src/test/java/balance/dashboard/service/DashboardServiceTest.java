package balance.dashboard.service;

import balance.catalog.repository.ProductRepository;
import balance.dashboard.dto.DashboardDTO;
import balance.inventory.model.InventoryStock;
import balance.inventory.repository.InventoryStockRepository;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.sales.model.Sale;
import balance.sales.model.Shift;
import balance.sales.repository.SaleRepository;
import balance.sales.repository.ShiftRepository;
import balance.tenant.context.TenantContext;
import balance.catalog.model.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long STORE_ID  = 1L;

    @InjectMocks private DashboardService dashboardService;

    @Mock private StoreRepository          storeRepository;
    @Mock private ShiftRepository          shiftRepository;
    @Mock private SaleRepository           saleRepository;
    @Mock private InventoryStockRepository stockRepository;
    @Mock private ProductRepository        productRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id) {
        Store s = new Store();
        s.setId(id); s.setName("Local " + id); s.setActive(true); s.setTenantId(TENANT_ID);
        return s;
    }

    private Shift buildShift(Long id, String code, String user) {
        // Shift no tiene setId/setOpenedAt públicos → usamos mock
        Shift sh = org.mockito.Mockito.mock(Shift.class);
        org.mockito.Mockito.lenient().when(sh.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(sh.getCode()).thenReturn(code);
        org.mockito.Mockito.lenient().when(sh.getUsername()).thenReturn(user);
        org.mockito.Mockito.lenient().when(sh.getStatus()).thenReturn("OPEN");
        org.mockito.Mockito.lenient().when(sh.getOpenedAt()).thenReturn(LocalDateTime.now());
        org.mockito.Mockito.lenient().when(sh.getStore()).thenReturn(buildStore(STORE_ID));
        return sh;
    }

    private Sale buildSale(BigDecimal total) {
        Sale s = new Sale();
        s.setTotal(total); s.setSubtotal(total); s.setIsv(BigDecimal.ZERO);
        s.setStatus("OPEN"); s.setSaleDate(LocalDate.now()); s.setUsername("cajero");
        s.setStore(buildStore(STORE_ID)); s.setTenantId(TENANT_ID);
        return s;
    }

    private InventoryStock buildStock(int qty, BigDecimal price) {
        Product p = new Product();
        p.setId(1L); p.setName("Producto"); p.setPrice(price); p.setActive(true); p.setTenantId(TENANT_ID);
        InventoryStock st = new InventoryStock();
        st.setProduct(p); st.setQuantity(qty); st.setTenantId(TENANT_ID);
        return st;
    }

    private void stubEmptyStoreData(Long storeId) {
        lenient().when(shiftRepository.findByStoreIdAndStatusAndTenantId(storeId, "OPEN", TENANT_ID))
                .thenReturn(Optional.empty());
        lenient().when(stockRepository.countLowStockByStoreIdAndTenantId(storeId, TENANT_ID))
                .thenReturn(0L);
        lenient().when(productRepository.findByStoreIdOrderByNameAsc(storeId))
                .thenReturn(List.of());
        lenient().when(stockRepository.findByStoreIdAndTenantIdOrderByProductNameAsc(storeId, TENANT_ID))
                .thenReturn(List.of());
        lenient().when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(storeId), eq(TENANT_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
    }

    // ── getDashboard — sin stores ─────────────────────────────────────────────

    @Test
    void getDashboard_returnsEmptyStores_whenNoActiveStores() {
        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard();

        assertThat(result.getStores()).isEmpty();
        assertThat(result.getTotalSalesToday()).isZero();
        assertThat(result.getTotalAmountToday()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── getDashboard — sin turno activo ───────────────────────────────────────

    @Test
    void getDashboard_storeWithNoActiveShift_hasActiveShiftFalse() {
        Store store = buildStore(STORE_ID);
        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(store));
        stubEmptyStoreData(STORE_ID);

        DashboardDTO result = dashboardService.getDashboard();

        assertThat(result.getStores()).hasSize(1);
        assertThat(result.getStores().get(0).isHasActiveShift()).isFalse();
    }

    @Test
    void getDashboard_storeWithNoActiveShift_shiftSalesTotalIsZero() {
        Store store = buildStore(STORE_ID);
        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(store));
        stubEmptyStoreData(STORE_ID);

        DashboardDTO result = dashboardService.getDashboard();

        assertThat(result.getStores().get(0).getShiftSalesTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── getDashboard — con turno activo ───────────────────────────────────────

    @Test
    void getDashboard_storeWithActiveShift_showsShiftInfo() {
        Store store = buildStore(STORE_ID);
        Shift shift = buildShift(1L, "T-20260521-0900-LOC", "cajero01");
        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(store));
        when(shiftRepository.findByStoreIdAndStatusAndTenantId(STORE_ID, "OPEN", TENANT_ID))
                .thenReturn(Optional.of(shift));
        when(saleRepository.findOpenByShiftIdAndTenantId(1L, TENANT_ID)).thenReturn(List.of());
        when(stockRepository.countLowStockByStoreIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(0L);
        when(productRepository.findByStoreIdOrderByNameAsc(STORE_ID)).thenReturn(List.of());
        when(stockRepository.findByStoreIdAndTenantIdOrderByProductNameAsc(STORE_ID, TENANT_ID)).thenReturn(List.of());
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard();

        var storeDTO = result.getStores().get(0);
        assertThat(storeDTO.isHasActiveShift()).isTrue();
        assertThat(storeDTO.getShiftCode()).isEqualTo("T-20260521-0900-LOC");
        assertThat(storeDTO.getShiftUsername()).isEqualTo("cajero01");
    }

    @Test
    void getDashboard_activeShiftWithSales_calculatesShiftTotal() {
        Store store = buildStore(STORE_ID);
        Shift shift = buildShift(1L, "T-001", "cajero01");
        Sale s1 = buildSale(new BigDecimal("150.00"));
        Sale s2 = buildSale(new BigDecimal("250.00"));
        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(store));
        when(shiftRepository.findByStoreIdAndStatusAndTenantId(STORE_ID, "OPEN", TENANT_ID))
                .thenReturn(Optional.of(shift));
        when(saleRepository.findOpenByShiftIdAndTenantId(1L, TENANT_ID)).thenReturn(List.of(s1, s2));
        when(stockRepository.countLowStockByStoreIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(0L);
        when(productRepository.findByStoreIdOrderByNameAsc(STORE_ID)).thenReturn(List.of());
        when(stockRepository.findByStoreIdAndTenantIdOrderByProductNameAsc(STORE_ID, TENANT_ID)).thenReturn(List.of());
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of(s1, s2));

        DashboardDTO result = dashboardService.getDashboard();

        var storeDTO = result.getStores().get(0);
        assertThat(storeDTO.getShiftSalesCount()).isEqualTo(2);
        assertThat(storeDTO.getShiftSalesTotal()).isEqualByComparingTo("400.00");
    }

    // ── getDashboard — inventario ─────────────────────────────────────────────

    @Test
    void getDashboard_calculatesEstimatedInventoryValue() {
        Store store = buildStore(STORE_ID);
        InventoryStock stock1 = buildStock(10, new BigDecimal("50.00"));  // 500
        InventoryStock stock2 = buildStock(5,  new BigDecimal("200.00")); // 1000
        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(store));
        when(shiftRepository.findByStoreIdAndStatusAndTenantId(STORE_ID, "OPEN", TENANT_ID))
                .thenReturn(Optional.empty());
        when(stockRepository.countLowStockByStoreIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(2L);
        when(productRepository.findByStoreIdOrderByNameAsc(STORE_ID)).thenReturn(List.of());
        when(stockRepository.findByStoreIdAndTenantIdOrderByProductNameAsc(STORE_ID, TENANT_ID))
                .thenReturn(List.of(stock1, stock2));
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(
                eq(STORE_ID), eq(TENANT_ID), any(), any())).thenReturn(List.of());

        DashboardDTO result = dashboardService.getDashboard();

        var storeDTO = result.getStores().get(0);
        assertThat(storeDTO.getEstimatedValue()).isEqualByComparingTo("1500.00"); // 500 + 1000
        assertThat(storeDTO.getLowStockCount()).isEqualTo(2L);
    }

    // ── getDashboard — totales globales ───────────────────────────────────────

    @Test
    void getDashboard_sumsTotalSalesAcrossAllStores() {
        Store storeA = buildStore(1L);
        Store storeB = buildStore(2L);
        Sale saleA = buildSale(new BigDecimal("100.00"));
        Sale saleB = buildSale(new BigDecimal("200.00"));

        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(storeA, storeB));
        stubEmptyStoreData(1L);
        stubEmptyStoreData(2L);
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(eq(1L), eq(TENANT_ID), any(), any()))
                .thenReturn(List.of(saleA));
        when(saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(eq(2L), eq(TENANT_ID), any(), any()))
                .thenReturn(List.of(saleB));

        DashboardDTO result = dashboardService.getDashboard();

        assertThat(result.getTotalSalesToday()).isEqualTo(2L);
        assertThat(result.getTotalAmountToday()).isEqualByComparingTo("300.00");
    }
}
