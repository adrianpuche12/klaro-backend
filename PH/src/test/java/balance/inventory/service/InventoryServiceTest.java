package balance.inventory.service;

import balance.catalog.model.Product;
import balance.catalog.repository.CategoryRepository;
import balance.catalog.repository.ProductRepository;
import balance.inventory.dto.StockAdjustmentDTO;
import balance.inventory.dto.StockItemDTO;
import balance.inventory.model.InventoryMovement;
import balance.inventory.model.InventoryStock;
import balance.inventory.repository.InventoryMovementRepository;
import balance.inventory.repository.InventoryStockRepository;
import balance.model.Store;
import balance.repository.StoreRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final Long TENANT_ID = 1L;

    @InjectMocks private InventoryService inventoryService;

    @Mock private InventoryStockRepository    stockRepository;
    @Mock private InventoryMovementRepository movementRepository;
    @Mock private ProductRepository           productRepository;
    @Mock private StoreRepository             storeRepository;
    @Mock private CategoryRepository          categoryRepository;
    @Mock private jakarta.persistence.EntityManager entityManager;

    @BeforeEach
    void setTenantContext() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id) {
        Store s = new Store();
        s.setId(id);
        s.setName("Danli");
        s.setTenantId(TENANT_ID);
        return s;
    }

    private Product buildProduct(Long id, int minStock) {
        Product p = new Product();
        p.setId(id);
        p.setName("Pollo");
        p.setPrice(new BigDecimal("100.00"));
        p.setMinStock(minStock);
        p.setActive(true);
        p.setTenantId(TENANT_ID);
        return p;
    }

    private InventoryStock buildStock(Product p, Store s, int qty) {
        InventoryStock stock = new InventoryStock();
        stock.setProduct(p);
        stock.setStore(s);
        stock.setQuantity(qty);
        stock.setTenantId(TENANT_ID);
        return stock;
    }

    private StockAdjustmentDTO buildAdj(Long productId, String type, int qty) {
        StockAdjustmentDTO dto = new StockAdjustmentDTO();
        dto.setProductId(productId);
        dto.setType(type);
        dto.setQuantity(qty);
        dto.setReason("Test");
        dto.setUsername("admin");
        return dto;
    }

    // El stock se mueve con UPDATE relativos y atómicos en la base (ver InventoryStockRepository):
    // estos tests verifican QUÉ operación se pide; la atomicidad real contra Postgres la prueba
    // VentasSinCarrerasTest (20 ventas en paralelo del último ítem).

    private void stubStoreAndProduct(Store store, Product product) {
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
    }

    private void stubReload(InventoryStock stock) {
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));
    }

    // ── adjust — ENTRADA ──────────────────────────────────────────────────────

    @Test
    void adjust_ENTRADA_sumaConUpdateAtomico() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 5);
        stubStoreAndProduct(store, product);
        when(stockRepository.increment(TENANT_ID, 1L, 1L, 5)).thenReturn(1);
        stubReload(buildStock(product, store, 15));

        StockItemDTO result = inventoryService.adjust(1L, buildAdj(1L, "ENTRADA", 5));

        verify(stockRepository).insertIfMissing(TENANT_ID, 1L, 1L);
        verify(stockRepository).increment(TENANT_ID, 1L, 1L, 5);
        verify(stockRepository, never()).decrementIfAvailable(anyLong(), anyLong(), anyLong(), anyInt());
        verify(stockRepository, never()).save(any());   // nunca leer-modificar-escribir
        verify(entityManager).refresh(any(InventoryStock.class));
        assertThat(result.getQuantity()).isEqualTo(15);
    }

    // ── adjust — SALIDA ───────────────────────────────────────────────────────

    @Test
    void adjust_SALIDA_descuentaConUpdateCondicionado() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        stubStoreAndProduct(store, product);
        when(stockRepository.decrementIfAvailable(TENANT_ID, 1L, 1L, 3)).thenReturn(1);
        stubReload(buildStock(product, store, 7));

        StockItemDTO result = inventoryService.adjust(1L, buildAdj(1L, "SALIDA", 3));

        verify(stockRepository).decrementIfAvailable(TENANT_ID, 1L, 1L, 3);
        verify(stockRepository, never()).increment(anyLong(), anyLong(), anyLong(), anyInt());
        verify(stockRepository, never()).save(any());
        assertThat(result.getQuantity()).isEqualTo(7);
    }

    @Test
    void adjust_SALIDA_sinStockSuficiente_lanza409SinRegistrarMovimiento() {
        stubStoreAndProduct(buildStore(1L), buildProduct(1L, 0));
        when(stockRepository.decrementIfAvailable(TENANT_ID, 1L, 1L, 5)).thenReturn(0);

        assertThatThrownBy(() -> inventoryService.adjust(1L, buildAdj(1L, "SALIDA", 5)))
                .isInstanceOf(InsufficientStockException.class)
                .isInstanceOf(balance.common.exception.ConflictException.class)
                .hasMessage("Stock insuficiente para Pollo");
        verify(movementRepository, never()).save(any());
    }

    @Test
    void adjust_cantidadNoPositiva_esRechazada() {
        stubStoreAndProduct(buildStore(1L), buildProduct(1L, 0));

        assertThatThrownBy(() -> inventoryService.adjust(1L, buildAdj(1L, "ENTRADA", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mayor a 0");
        assertThatThrownBy(() -> inventoryService.adjust(1L, buildAdj(1L, "SALIDA", -3)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(movementRepository);
        verify(stockRepository, never()).increment(anyLong(), anyLong(), anyLong(), anyInt());
        verify(stockRepository, never()).decrementIfAvailable(anyLong(), anyLong(), anyLong(), anyInt());
    }

    // ── adjustSilent ──────────────────────────────────────────────────────────

    @Test
    void adjustSilent_doesNotThrowWhenStockInsufficient() {
        stubStoreAndProduct(buildStore(1L), buildProduct(1L, 0));
        when(stockRepository.decrementIfAvailable(TENANT_ID, 1L, 1L, 999)).thenReturn(0);

        assertThatCode(() -> inventoryService.adjustSilent(1L, buildAdj(1L, "SALIDA", 999)))
                .doesNotThrowAnyException();
        verify(movementRepository, never()).save(any());
    }

    // ── movimiento registrado ─────────────────────────────────────────────────

    @Test
    void adjust_registersMovementWithCorrectFields() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);

        StockAdjustmentDTO dto = new StockAdjustmentDTO();
        dto.setProductId(1L);
        dto.setType("AJUSTE");
        dto.setQuantity(10);
        dto.setReason("Conteo físico");
        dto.setNotes("Diferencia mensual");
        dto.setUsername("admin");

        stubStoreAndProduct(store, product);
        when(stockRepository.increment(TENANT_ID, 1L, 1L, 10)).thenReturn(1);
        stubReload(buildStock(product, store, 30));

        inventoryService.adjust(1L, dto);

        ArgumentCaptor<InventoryMovement> captor = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(movementRepository).save(captor.capture());
        InventoryMovement saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("AJUSTE");
        assertThat(saved.getQuantity()).isEqualTo(10);
        assertThat(saved.getReason()).isEqualTo("Conteo físico");
        assertThat(saved.getNotes()).isEqualTo("Diferencia mensual");
        assertThat(saved.getUsername()).isEqualTo("admin");
    }

    // ── auto-crear stock ──────────────────────────────────────────────────────

    @Test
    void adjust_createsStockRecordIfNotExists_sinCarrera() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        stubStoreAndProduct(store, product);
        when(stockRepository.increment(TENANT_ID, 1L, 1L, 10)).thenReturn(1);
        stubReload(buildStock(product, store, 10));

        inventoryService.adjust(1L, buildAdj(1L, "ENTRADA", 10));

        // INSERT ... ON CONFLICT DO NOTHING antes del UPDATE: dos altas simultáneas no chocan.
        var inOrder = inOrder(stockRepository);
        inOrder.verify(stockRepository).insertIfMissing(TENANT_ID, 1L, 1L);
        inOrder.verify(stockRepository).increment(TENANT_ID, 1L, 1L, 10);
    }

    // ── validaciones ──────────────────────────────────────────────────────────

    @Test
    void adjust_throwsWhenStoreNotFound() {
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.adjust(99L, buildAdj(1L, "ENTRADA", 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local no encontrado");
    }

    @Test
    void adjust_throwsWhenProductNotFound() {
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L)));
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.adjust(1L, buildAdj(99L, "ENTRADA", 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Producto no encontrado");
    }
}
