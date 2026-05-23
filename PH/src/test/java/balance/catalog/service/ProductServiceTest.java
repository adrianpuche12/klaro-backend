package balance.catalog.service;

import balance.catalog.dto.ProductRequestDTO;
import balance.catalog.dto.ProductResponseDTO;
import balance.catalog.model.Product;
import balance.catalog.repository.CategoryRepository;
import balance.catalog.repository.ProductRepository;
import balance.inventory.repository.InventoryMovementRepository;
import balance.inventory.repository.InventoryStockRepository;
import balance.inventory.service.InventoryService;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long STORE_ID  = 1L;
    private static final Long PRODUCT_ID = 10L;

    @InjectMocks private ProductService productService;

    @Mock private ProductRepository           productRepository;
    @Mock private StoreRepository             storeRepository;
    @Mock private CategoryRepository          categoryRepository;
    @Mock private InventoryService            inventoryService;
    @Mock private InventoryStockRepository    inventoryStockRepository;
    @Mock private InventoryMovementRepository inventoryMovementRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    private Store buildStore() {
        Store s = new Store(); s.setId(STORE_ID); s.setName("Danli"); s.setTenantId(TENANT_ID); return s;
    }

    private Product buildProduct() {
        Product p = new Product();
        p.setId(PRODUCT_ID); p.setName("Pollo"); p.setPrice(new BigDecimal("100.00"));
        p.setActive(true); p.setTenantId(TENANT_ID); p.setType("SIMPLE"); p.setMinStock(0);
        p.setStore(buildStore());
        return p;
    }

    private ProductRequestDTO buildRequest(String name, BigDecimal price) {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setName(name); dto.setPrice(price); dto.setType("SIMPLE"); dto.setMinStock(0);
        return dto;
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_savesProductAndInitsStock() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(buildStore()));
        when(productRepository.save(any())).thenAnswer(inv -> {
            Product p = inv.getArgument(0); p.setId(PRODUCT_ID); return p;
        });

        Optional<ProductResponseDTO> result = productService.create(STORE_ID, buildRequest("Pollo", new BigDecimal("100.00")));

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Pollo");
        verify(inventoryService).initStock(any(Product.class), any(Store.class));
    }

    @Test
    void create_throwsWhenSkuAlreadyExists() {
        ProductRequestDTO dto = buildRequest("Pollo", new BigDecimal("100.00"));
        dto.setSku("SKU-001");
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(buildStore()));
        when(productRepository.existsBySkuAndStoreId("SKU-001", STORE_ID)).thenReturn(true);

        assertThatThrownBy(() -> productService.create(STORE_ID, dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU");
    }

    @Test
    void create_setsActiveToTrue() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(buildStore()));
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        when(productRepository.save(captor.capture())).thenAnswer(inv -> { Product p = inv.getArgument(0); p.setId(PRODUCT_ID); return p; });

        productService.create(STORE_ID, buildRequest("Pollo", new BigDecimal("50.00")));

        assertThat(captor.getValue().getActive()).isTrue();
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_modifiesExistingProduct() {
        Product existing = buildProduct();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ProductResponseDTO> result = productService.update(PRODUCT_ID, buildRequest("Pollo XL", new BigDecimal("120.00")));

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Pollo XL");
    }

    @Test
    void update_returnsEmptyWhenProductNotInTenant() {
        Product other = buildProduct();
        other.setTenantId(99L);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(other));

        Optional<ProductResponseDTO> result = productService.update(PRODUCT_ID, buildRequest("Otro", new BigDecimal("50.00")));

        assertThat(result).isEmpty();
        verify(productRepository, never()).save(any());
    }

    // ── toggle ────────────────────────────────────────────────────────────────

    @Test
    void toggle_deactivatesActiveProduct() {
        Product product = buildProduct(); // active = true
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ProductResponseDTO> result = productService.toggle(PRODUCT_ID);

        assertThat(result).isPresent();
        assertThat(product.getActive()).isFalse();
    }

    @Test
    void toggle_activatesInactiveProduct() {
        Product product = buildProduct();
        product.setActive(false);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        productService.toggle(PRODUCT_ID);

        assertThat(product.getActive()).isTrue();
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removesProductAndInventoryData() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(buildProduct()));

        boolean result = productService.delete(PRODUCT_ID);

        assertThat(result).isTrue();
        verify(inventoryMovementRepository).deleteByProductId(PRODUCT_ID);
        verify(inventoryStockRepository).deleteByProductId(PRODUCT_ID);
        verify(productRepository).deleteById(PRODUCT_ID);
    }

    @Test
    void delete_returnsFalseWhenProductNotFound() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(productService.delete(99L)).isFalse();
        verify(productRepository, never()).deleteById(any());
    }

    @Test
    void delete_returnsFalseWhenProductBelongsToOtherTenant() {
        Product other = buildProduct(); other.setTenantId(99L);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(other));

        assertThat(productService.delete(PRODUCT_ID)).isFalse();
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    void findById_returnsProductForCorrectTenant() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(buildProduct()));

        Optional<ProductResponseDTO> result = productService.findById(PRODUCT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Pollo");
    }

    @Test
    void findById_returnsEmptyForOtherTenant() {
        Product other = buildProduct(); other.setTenantId(99L);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(other));

        assertThat(productService.findById(PRODUCT_ID)).isEmpty();
    }

    // ── findByStore ───────────────────────────────────────────────────────────

    @Test
    void findByStore_filtersActiveWhenActiveParamGiven() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(buildStore()));
        when(productRepository.findByStoreIdAndActiveOrderByNameAsc(STORE_ID, true)).thenReturn(List.of(buildProduct()));

        List<ProductResponseDTO> result = productService.findByStore(STORE_ID, true, null, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findByStore_searchesByTextWhenSearchGiven() {
        when(storeRepository.findByIdAndTenantId(STORE_ID, TENANT_ID)).thenReturn(Optional.of(buildStore()));
        when(productRepository.searchByStoreId(STORE_ID, "pollo")).thenReturn(List.of(buildProduct()));

        List<ProductResponseDTO> result = productService.findByStore(STORE_ID, null, null, "pollo");

        assertThat(result).hasSize(1);
        verify(productRepository).searchByStoreId(STORE_ID, "pollo");
    }
}