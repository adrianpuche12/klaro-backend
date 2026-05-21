package balance.inventory.repository;

import balance.catalog.model.Product;
import balance.catalog.repository.ProductRepository;
import balance.inventory.model.InventoryStock;
import balance.model.Store;
import balance.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(locations = "classpath:application-test.properties")
class InventoryStockRepositoryIT {

    private static final Long TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 2L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired InventoryStockRepository stockRepository;
    @Autowired StoreRepository          storeRepository;
    @Autowired ProductRepository        productRepository;

    private Store store;

    @BeforeEach
    void setup() {
        stockRepository.deleteAll();
        productRepository.deleteAll();
        storeRepository.deleteAll();

        store = new Store();
        store.setName("Danli Test");
        store.setTenantId(TENANT_ID);
        store = storeRepository.save(store);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Product saveProduct(String name, int minStock) {
        return saveProduct(name, minStock, store, TENANT_ID);
    }

    private Product saveProduct(String name, int minStock, Store s, Long tenantId) {
        Product p = new Product();
        p.setName(name);
        p.setPrice(new BigDecimal("100.00"));
        p.setMinStock(minStock);
        p.setActive(true);
        p.setStore(s);
        p.setTenantId(tenantId);
        return productRepository.save(p);
    }

    private InventoryStock saveStock(Product product, int quantity) {
        return saveStock(product, quantity, store, TENANT_ID);
    }

    private InventoryStock saveStock(Product product, int quantity, Store s, Long tenantId) {
        InventoryStock stock = new InventoryStock();
        stock.setProduct(product);
        stock.setStore(s);
        stock.setQuantity(quantity);
        stock.setTenantId(tenantId);
        return stockRepository.save(stock);
    }

    // ── findLowStockByStoreIdAndTenantId ──────────────────────────────────────

    @Test
    void findLowStock_returnsProductBelowMinStock() {
        Product p = saveProduct("Pollo", 5);
        saveStock(p, 3);

        List<InventoryStock> result = stockRepository.findLowStockByStoreIdAndTenantId(
                store.getId(), TENANT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProduct().getName()).isEqualTo("Pollo");
    }

    @Test
    void findLowStock_excludesProductAboveMinStock() {
        Product p = saveProduct("Pollo", 5);
        saveStock(p, 10);

        List<InventoryStock> result = stockRepository.findLowStockByStoreIdAndTenantId(
                store.getId(), TENANT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findLowStock_excludesProductWithMinStockZero() {
        Product p = saveProduct("Sin mínimo", 0);
        saveStock(p, 0);

        List<InventoryStock> result = stockRepository.findLowStockByStoreIdAndTenantId(
                store.getId(), TENANT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findLowStock_includesProductAtExactMinStock() {
        Product p = saveProduct("Pollo en límite", 5);
        saveStock(p, 5);

        List<InventoryStock> result = stockRepository.findLowStockByStoreIdAndTenantId(
                store.getId(), TENANT_ID);

        assertThat(result).hasSize(1);
    }

    @Test
    void findLowStock_excludesOtherTenants() {
        Product p = saveProduct("Pollo", 5, store, OTHER_TENANT_ID);
        saveStock(p, 1, store, OTHER_TENANT_ID);

        List<InventoryStock> result = stockRepository.findLowStockByStoreIdAndTenantId(
                store.getId(), TENANT_ID);

        assertThat(result).isEmpty();
    }

    // ── countLowStockByStoreIdAndTenantId ─────────────────────────────────────

    @Test
    void countLowStock_returnsCorrectCount() {
        Product p1 = saveProduct("Bajo1", 5); saveStock(p1, 1);
        Product p2 = saveProduct("Bajo2", 5); saveStock(p2, 2);
        Product p3 = saveProduct("OK",    5); saveStock(p3, 10);

        long count = stockRepository.countLowStockByStoreIdAndTenantId(store.getId(), TENANT_ID);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void countLowStock_returnsZeroWhenNoLowStock() {
        Product p = saveProduct("OK", 5);
        saveStock(p, 10);

        long count = stockRepository.countLowStockByStoreIdAndTenantId(store.getId(), TENANT_ID);

        assertThat(count).isZero();
    }

    // ── findByProductIdAndStoreIdAndTenantId ──────────────────────────────────

    @Test
    void findByProductAndStore_returnsStockWhenExists() {
        Product p = saveProduct("Pollo", 5);
        saveStock(p, 10);

        Optional<InventoryStock> result = stockRepository.findByProductIdAndStoreIdAndTenantId(
                p.getId(), store.getId(), TENANT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getQuantity()).isEqualTo(10);
    }

    @Test
    void findByProductAndStore_returnsEmptyForOtherTenant() {
        Product p = saveProduct("Pollo", 5, store, OTHER_TENANT_ID);
        saveStock(p, 10, store, OTHER_TENANT_ID);

        Optional<InventoryStock> result = stockRepository.findByProductIdAndStoreIdAndTenantId(
                p.getId(), store.getId(), TENANT_ID);

        assertThat(result).isEmpty();
    }

    // ── findByStoreIdAndTenantIdOrderByProductNameAsc ─────────────────────────

    @Test
    void findByStoreAndTenant_returnsInAlphabeticOrder() {
        Product pZ = saveProduct("Zapallo", 0); saveStock(pZ, 5);
        Product pA = saveProduct("Ala",     0); saveStock(pA, 3);
        Product pM = saveProduct("Muslo",   0); saveStock(pM, 8);

        List<InventoryStock> result = stockRepository.findByStoreIdAndTenantIdOrderByProductNameAsc(
                store.getId(), TENANT_ID);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getProduct().getName()).isEqualTo("Ala");
        assertThat(result.get(1).getProduct().getName()).isEqualTo("Muslo");
        assertThat(result.get(2).getProduct().getName()).isEqualTo("Zapallo");
    }

    @Test
    void findByStoreAndTenant_excludesOtherTenants() {
        Product pMio  = saveProduct("Mio",   0, store, TENANT_ID);   saveStock(pMio,  5, store, TENANT_ID);
        Product pOtro = saveProduct("Ajeno", 0, store, OTHER_TENANT_ID); saveStock(pOtro, 3, store, OTHER_TENANT_ID);

        List<InventoryStock> result = stockRepository.findByStoreIdAndTenantIdOrderByProductNameAsc(
                store.getId(), TENANT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProduct().getName()).isEqualTo("Mio");
    }
}
