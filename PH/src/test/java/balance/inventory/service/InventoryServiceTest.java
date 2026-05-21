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

    // ── adjust — ENTRADA ──────────────────────────────────────────────────────

    @Test
    void adjust_ENTRADA_increasesQuantityCorrectly() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 5);
        InventoryStock stock = buildStock(product, store, 10);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.adjust(1L, buildAdj(1L, "ENTRADA", 5));

        assertThat(stock.getQuantity()).isEqualTo(15);
        verify(stockRepository).save(stock);
    }

    // ── adjust — SALIDA ───────────────────────────────────────────────────────

    @Test
    void adjust_SALIDA_decreasesQuantityCorrectly() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        InventoryStock stock = buildStock(product, store, 10);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.adjust(1L, buildAdj(1L, "SALIDA", 3));

        assertThat(stock.getQuantity()).isEqualTo(7);
    }

    @Test
    void adjust_SALIDA_throwsWhenStockInsufficient() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        InventoryStock stock = buildStock(product, store, 2);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> inventoryService.adjust(1L, buildAdj(1L, "SALIDA", 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    @Test
    void adjust_SALIDA_throwsWhenQuantityExactlyExceedsStock() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        InventoryStock stock = buildStock(product, store, 5);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> inventoryService.adjust(1L, buildAdj(1L, "SALIDA", 6)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjust_SALIDA_succeedsWhenQuantityEqualsStock() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        InventoryStock stock = buildStock(product, store, 5);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.adjust(1L, buildAdj(1L, "SALIDA", 5));

        assertThat(stock.getQuantity()).isEqualTo(0);
    }

    // ── adjustSilent ──────────────────────────────────────────────────────────

    @Test
    void adjustSilent_doesNotThrowWhenStockInsufficient() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        InventoryStock stock = buildStock(product, store, 0);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));

        assertThatCode(() -> inventoryService.adjustSilent(1L, buildAdj(1L, "SALIDA", 999)))
                .doesNotThrowAnyException();
    }

    @Test
    void adjustSilent_doesNotSaveWhenStockInsufficient() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        InventoryStock stock = buildStock(product, store, 1);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));

        inventoryService.adjustSilent(1L, buildAdj(1L, "SALIDA", 999));

        verify(stockRepository, never()).save(any());
    }

    // ── movimiento registrado ─────────────────────────────────────────────────

    @Test
    void adjust_registersMovementWithCorrectFields() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        InventoryStock stock = buildStock(product, store, 20);

        StockAdjustmentDTO dto = new StockAdjustmentDTO();
        dto.setProductId(1L);
        dto.setType("AJUSTE");
        dto.setQuantity(10);
        dto.setReason("Conteo físico");
        dto.setNotes("Diferencia mensual");
        dto.setUsername("admin");

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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

    @Test
    void adjust_alwaysRegistersMovementRegardlessOfType() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);
        InventoryStock stock = buildStock(product, store, 10);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.adjust(1L, buildAdj(1L, "ENTRADA", 5));

        verify(movementRepository, times(1)).save(any(InventoryMovement.class));
    }

    // ── auto-crear stock ──────────────────────────────────────────────────────

    @Test
    void adjust_createsStockRecordIfNotExists() {
        Store store = buildStore(1L);
        Product product = buildProduct(1L, 0);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(stockRepository.findByProductIdAndStoreIdAndTenantId(1L, 1L, TENANT_ID)).thenReturn(Optional.empty());
        when(stockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        inventoryService.adjust(1L, buildAdj(1L, "ENTRADA", 10));

        ArgumentCaptor<InventoryStock> captor = ArgumentCaptor.forClass(InventoryStock.class);
        verify(stockRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(s -> s.getQuantity() == 10);
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
