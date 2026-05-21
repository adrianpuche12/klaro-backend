package balance.sales.service;

import balance.catalog.model.Product;
import balance.catalog.repository.ProductRepository;
import balance.inventory.dto.StockAdjustmentDTO;
import balance.inventory.service.InventoryService;
import balance.model.ClosingDeposit;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.sales.dto.SaleItemRequestDTO;
import balance.sales.dto.SaleRequestDTO;
import balance.sales.dto.SaleResponseDTO;
import balance.sales.model.Sale;
import balance.sales.model.SaleItem;
import balance.sales.model.Shift;
import balance.sales.repository.SaleRepository;
import balance.sales.repository.ShiftRepository;
import balance.service.FormsService;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SalesServiceTest {

    private static final Long TENANT_ID = 1L;

    @InjectMocks private SalesService salesService;

    @Mock private SaleRepository      saleRepository;
    @Mock private ShiftRepository     shiftRepository;
    @Mock private ProductRepository   productRepository;
    @Mock private StoreRepository     storeRepository;
    @Mock private InventoryService    inventoryService;
    @Mock private FormsService        formsService;

    @BeforeEach
    void setTenantContext() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id, String name) {
        Store s = new Store();
        s.setId(id);
        s.setName(name);
        s.setTenantId(TENANT_ID);
        return s;
    }

    private Shift buildShift(Long id, String status) {
        Shift shift = new Shift();
        shift.setStore(buildStore(1L, "Danli"));
        shift.setStatus(status);
        shift.setCode("T-20260514-0900-DAN");
        shift.setUsername("cajero01");
        shift.setTenantId(TENANT_ID);
        return shift;
    }

    private Product buildProduct(Long id, String name, BigDecimal price) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(price);
        p.setActive(true);
        p.setTenantId(TENANT_ID);
        return p;
    }

    private SaleRequestDTO buildRequest(String username, Long productId, int qty) {
        SaleItemRequestDTO item = new SaleItemRequestDTO();
        item.setProductId(productId);
        item.setQuantity(qty);
        SaleRequestDTO req = new SaleRequestDTO();
        req.setUsername(username);
        req.setItems(List.of(item));
        return req;
    }

    // ── createSale — cálculo financiero ───────────────────────────────────────

    @Test
    void createSale_calculatesCorrectly() {
        // ISV_RATE = 0, subtotal = 100 * 2 = 200.00, isv = 0, total = 200.00
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "OPEN")));
        when(productRepository.findById(1L)).thenReturn(Optional.of(buildProduct(1L, "Pollo", new BigDecimal("100.00"))));
        when(saleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaleResponseDTO result = salesService.createSale(1L, buildRequest("cajero01", 1L, 2));

        assertThat(result.getSubtotal()).isEqualByComparingTo("200.00");
        assertThat(result.getIsv()).isEqualByComparingTo("0.00");
        assertThat(result.getTotal()).isEqualByComparingTo("200.00");
    }

    @Test
    void createSale_isvIsConsistentWithRate() {
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "OPEN")));
        when(productRepository.findById(1L)).thenReturn(Optional.of(buildProduct(1L, "Ala", new BigDecimal("33.33"))));
        when(saleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SaleResponseDTO result = salesService.createSale(1L, buildRequest("cajero01", 1L, 3));

        // total = subtotal + isv (siempre consistente)
        assertThat(result.getTotal()).isEqualByComparingTo(result.getSubtotal().add(result.getIsv()));
    }

    // ── createSale — snapshot de producto ─────────────────────────────────────

    @Test
    void createSale_savesProductNameAndPriceSnapshot() {
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "OPEN")));
        when(productRepository.findById(1L)).thenReturn(Optional.of(buildProduct(1L, "Pollo Entero", new BigDecimal("150.00"))));
        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        when(saleRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        salesService.createSale(1L, buildRequest("cajero01", 1L, 1));

        SaleItem savedItem = captor.getValue().getItems().get(0);
        assertThat(savedItem.getProductNameSnapshot()).isEqualTo("Pollo Entero");
        assertThat(savedItem.getUnitPriceSnapshot()).isEqualByComparingTo("150.00");
    }

    // ── createSale — validaciones de negocio ──────────────────────────────────

    @Test
    void createSale_throwsWhenShiftNotFound() {
        when(shiftRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.createSale(99L, buildRequest("cajero", 1L, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Turno no encontrado");
    }

    @Test
    void createSale_throwsWhenShiftIsClosed() {
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "CLOSED")));

        assertThatThrownBy(() -> salesService.createSale(1L, buildRequest("cajero", 1L, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turno ya está cerrado");
    }

    @Test
    void createSale_throwsWhenProductNotFound() {
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "OPEN")));
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.createSale(1L, buildRequest("cajero", 99L, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Producto no encontrado");
    }

    @Test
    void createSale_throwsWhenProductIsInactive() {
        Product inactivo = buildProduct(1L, "Pollo", new BigDecimal("100.00"));
        inactivo.setActive(false);
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "OPEN")));
        when(productRepository.findById(1L)).thenReturn(Optional.of(inactivo));

        assertThatThrownBy(() -> salesService.createSale(1L, buildRequest("cajero", 1L, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Producto inactivo");
    }

    // ── createSale — stock ────────────────────────────────────────────────────

    @Test
    void createSale_callsAdjustSilentForEachItem() {
        SaleItemRequestDTO i1 = new SaleItemRequestDTO(); i1.setProductId(1L); i1.setQuantity(2);
        SaleItemRequestDTO i2 = new SaleItemRequestDTO(); i2.setProductId(2L); i2.setQuantity(1);
        SaleRequestDTO req = new SaleRequestDTO();
        req.setUsername("cajero");
        req.setItems(List.of(i1, i2));

        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "OPEN")));
        when(productRepository.findById(1L)).thenReturn(Optional.of(buildProduct(1L, "Pollo", new BigDecimal("100.00"))));
        when(productRepository.findById(2L)).thenReturn(Optional.of(buildProduct(2L, "Ala", new BigDecimal("50.00"))));
        when(saleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        salesService.createSale(1L, req);

        verify(inventoryService, times(2)).adjustSilent(eq(1L), any(StockAdjustmentDTO.class));
    }

    @Test
    void createSale_adjustSilentUsesTipoSalida() {
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "OPEN")));
        when(productRepository.findById(1L)).thenReturn(Optional.of(buildProduct(1L, "Pollo", new BigDecimal("100.00"))));
        when(saleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<StockAdjustmentDTO> captor = ArgumentCaptor.forClass(StockAdjustmentDTO.class);

        salesService.createSale(1L, buildRequest("cajero", 1L, 3));

        verify(inventoryService).adjustSilent(eq(1L), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("SALIDA");
        assertThat(captor.getValue().getQuantity()).isEqualTo(3);
    }

    // ── cancelSale ────────────────────────────────────────────────────────────

    @Test
    void cancelSale_deletesOpenSale() {
        Sale sale = new Sale();
        sale.setStatus("OPEN");
        sale.setStore(buildStore(1L, "Danli"));
        sale.setTenantId(TENANT_ID);

        when(saleRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(sale));

        salesService.cancelSale(1L);

        verify(saleRepository).delete(sale);
    }

    @Test
    void cancelSale_revertsStockWithEntrada() {
        Product product = buildProduct(1L, "Pollo", new BigDecimal("100.00"));
        SaleItem item = new SaleItem();
        item.setProduct(product);
        item.setQuantity(3);
        item.setProductNameSnapshot("Pollo");
        item.setUnitPriceSnapshot(new BigDecimal("100.00"));
        item.setSubtotal(new BigDecimal("300.00"));

        Sale sale = new Sale();
        sale.setStatus("OPEN");
        sale.setStore(buildStore(1L, "Danli"));
        sale.setTenantId(TENANT_ID);
        sale.getItems().add(item);

        when(saleRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(sale));
        ArgumentCaptor<StockAdjustmentDTO> captor = ArgumentCaptor.forClass(StockAdjustmentDTO.class);

        salesService.cancelSale(1L);

        verify(inventoryService).adjustSilent(eq(1L), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("ENTRADA");
        assertThat(captor.getValue().getQuantity()).isEqualTo(3);
    }

    @Test
    void cancelSale_throwsWhenSaleNotFound() {
        when(saleRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.cancelSale(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Venta no encontrada");
    }

    @Test
    void cancelSale_throwsWhenSaleIsConfirmed() {
        Sale sale = new Sale();
        sale.setStatus("CONFIRMED");
        sale.setStore(buildStore(1L, "Danli"));
        sale.setTenantId(TENANT_ID);

        when(saleRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> salesService.cancelSale(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se puede cancelar");
    }

    // ── closeShift ────────────────────────────────────────────────────────────

    @Test
    void closeShift_throwsWhenShiftAlreadyClosed() {
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "CLOSED")));

        assertThatThrownBy(() -> salesService.closeShift(1L, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turno ya está cerrado");
    }

    @Test
    void closeShift_throwsWhenNoOpenSales() {
        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildShift(1L, "OPEN")));
        when(saleRepository.findOpenByShiftIdAndTenantId(1L, TENANT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> salesService.closeShift(1L, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No hay ventas abiertas");
    }

    @Test
    void closeShift_confirmsAllOpenSalesAndSavesShift() {
        Shift shift = buildShift(1L, "OPEN");
        Sale sale1 = new Sale(); sale1.setStatus("OPEN");
        sale1.setTotal(new BigDecimal("115.00"));
        sale1.setStore(buildStore(1L, "Danli"));
        sale1.setTenantId(TENANT_ID);
        Sale sale2 = new Sale(); sale2.setStatus("OPEN");
        sale2.setTotal(new BigDecimal("230.00"));
        sale2.setStore(buildStore(1L, "Danli"));
        sale2.setTenantId(TENANT_ID);

        ClosingDeposit deposit = new ClosingDeposit();
        deposit.setId(10L);

        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(shift));
        when(saleRepository.findOpenByShiftIdAndTenantId(1L, TENANT_ID)).thenReturn(List.of(sale1, sale2));
        when(formsService.saveClosingDeposit(any())).thenReturn(deposit);
        when(saleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(shiftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        salesService.closeShift(1L, "admin");

        assertThat(sale1.getStatus()).isEqualTo("CONFIRMED");
        assertThat(sale2.getStatus()).isEqualTo("CONFIRMED");
        assertThat(shift.getStatus()).isEqualTo("CLOSED");
        assertThat(shift.getClosedAt()).isNotNull();
    }
}
