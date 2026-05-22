package balance.dashboard.service;

import balance.catalog.repository.ProductRepository;
import balance.common.enums.SaleStatus;
import balance.common.enums.ShiftStatus;
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

    @BeforeEach
    void setTenant() {
        TenantContext.setTenantId(TENANT_ID);
        // Defaults para las queries batch: sin ventas ni turnos abiertos
        lenient().when(saleRepository.findByTenantIdAndDateRangeStrict(eq(TENANT_ID), any(), any()))
                .thenReturn(List.of());
        lenient().when(shiftRepository.findByTenantIdAndStatus(TENANT_ID, ShiftStatus.OPEN))
                .thenReturn(List.of());
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id) {
        Store s = new Store();
        s.setId(id); s.setName("Local " + id); s.setActive(true); s.setTenantId(TENANT_ID);
        return s;
    }

    private Shift buildShift(Long id, String code, String user) {
        Shift sh = org.mockito.Mockito.mock(Shift.class);
        org.mockito.Mockito.lenient().when(sh.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(sh.getCode()).thenReturn(code);
        org.mockito.Mockito.lenient().when(sh.getUsername()).thenReturn(user);
        org.mockito.Mockito.lenient().when(sh.getStatus()).thenReturn(ShiftStatus.OPEN);
        org.mockito.Mockito.lenient().when(sh.getOpenedAt()).thenReturn(LocalDateTime.now());
        org.mockito.Mockito.lenient().when(sh.getStore()).thenReturn(buildStore(STORE_ID));
        return sh;
    }

    private Sale buildSale(BigDecimal total) {
        return buildSale(total, null);
    }

    private Sale buildSale(BigDecimal total, Shift shift) {
        Sale s = new Sale();
        s.setTotal(total); s.setSubtotal(total); s.setIsv(BigDecimal.ZERO);
        s.setStatus(SaleStatus.OPEN); s.setSaleDate(LocalDate.now()); s.setUsername("cajero");
        s.setStore(buildStore(STORE_ID)); s.setTenantId(TENANT_ID);
        if (shift != null) s.setShift(shift);
        return s;
    }

    private InventoryStock buildStock(int qty, BigDecimal price) {
        Product p = new Product();
        p.setId(1L); p.setName("Producto"); p.setPrice(price); p.setActive(true); p.setTenantId(TENANT_ID);
        InventoryStock st = new InventoryStock();
        st.setProduct(p); st.setQuantity(qty); st.setTenantId(TENANT_ID);
        return st;
    }

    /** Stubs de inventario vacíos por local (siguen siendo por local en la impl actual). */
    private void stubEmptyInventory(Long storeId) {
        lenient().when(stockRepository.countLowStockByStoreIdAndTenantId(storeId, TENANT_ID))
                .thenReturn(0L);
        lenient().when(productRepository.findByStoreIdOrderByNameAsc(storeId))
                .thenReturn(List.of());
        lenient().when(stockRepository.findByStoreIdAndTenantIdOrderByProductNameAsc(storeId, TENANT_ID))
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
        stubEmptyInventory(STORE_ID);

        DashboardDTO result = dashboardService.getDashboard();

        assertThat(result.getStores()).hasSize(1);
        assertThat(result.getStores().get(0).isHasActiveShift()).isFalse();
    }

    @Test
    void getDashboard_storeWithNoActiveShift_shiftSalesTotalIsZero() {
        Store store = buildStore(STORE_ID);
        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(store));
        stubEmptyInventory(STORE_ID);

        DashboardDTO result = dashboardService.getDashboard();

        assertThat(result.getStores().get(0).getShiftSalesTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── getDashboard — con turno activo ───────────────────────────────────────

    @Test
    void getDashboard_storeWithActiveShift_showsShiftInfo() {
        Store store = buildStore(STORE_ID);
        Shift shift = buildShift(1L, "T-20260521-0900-LOC", "cajero01");

        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(store));
        when(shiftRepository.findByTenantIdAndStatus(TENANT_ID, ShiftStatus.OPEN)).thenReturn(List.of(shift));
        when(saleRepository.findOpenByShiftIdsAndTenantId(List.of(1L), TENANT_ID)).thenReturn(List.of());
        stubEmptyInventory(STORE_ID);

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
        Sale s1 = buildSale(new BigDecimal("150.00"), shift);
        Sale s2 = buildSale(new BigDecimal("250.00"), shift);

        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(store));
        when(shiftRepository.findByTenantIdAndStatus(TENANT_ID, ShiftStatus.OPEN)).thenReturn(List.of(shift));
        when(saleRepository.findOpenByShiftIdsAndTenantId(List.of(1L), TENANT_ID)).thenReturn(List.of(s1, s2));
        when(saleRepository.findByTenantIdAndDateRangeStrict(eq(TENANT_ID), any(), any()))
                .thenReturn(List.of(s1, s2));
        stubEmptyInventory(STORE_ID);

        DashboardDTO result = dashboardService.getDashboard();

        var storeDTO = result.getStores().get(0);
        assertThat(storeDTO.getShiftSalesCount()).isEqualTo(2);
        assertThat(storeDTO.getShiftSalesTotal()).isEqualByComparingTo("400.00");
    }

    // ── getDashboard — inventario ─────────────────────────────────────────────

    @Test
    void getDashboard_calculatesEstimatedInventoryValue() {
        Store store = buildStore(STORE_ID);
        InventoryStock stock1 = buildStock(10, new BigDecimal("50.00"));
        InventoryStock stock2 = buildStock(5,  new BigDecimal("200.00"));

        when(storeRepository.findByTenantIdAndActive(TENANT_ID, true)).thenReturn(List.of(store));
        when(stockRepository.countLowStockByStoreIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(2L);
        when(productRepository.findByStoreIdOrderByNameAsc(STORE_ID)).thenReturn(List.of());
        when(stockRepository.findByStoreIdAndTenantIdOrderByProductNameAsc(STORE_ID, TENANT_ID))
                .thenReturn(List.of(stock1, stock2));

        DashboardDTO result = dashboardService.getDashboard();

        var storeDTO = result.getStores().get(0);
        assertThat(storeDTO.getEstimatedValue()).isEqualByComparingTo("1500.00");
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
        when(saleRepository.findByTenantIdAndDateRangeStrict(eq(TENANT_ID), any(), any()))
                .thenReturn(List.of(saleA, saleB));
        stubEmptyInventory(1L);
        stubEmptyInventory(2L);

        DashboardDTO result = dashboardService.getDashboard();

        assertThat(result.getTotalSalesToday()).isEqualTo(2L);
        assertThat(result.getTotalAmountToday()).isEqualByComparingTo("300.00");
    }
}
