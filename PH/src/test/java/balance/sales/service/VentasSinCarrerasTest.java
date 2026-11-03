package balance.sales.service;

import balance.common.exception.ConflictException;
import balance.inventory.dto.StockAdjustmentDTO;
import balance.inventory.service.InsufficientStockException;
import balance.inventory.service.InventoryService;
import balance.sales.dto.SaleItemRequestDTO;
import balance.sales.dto.SaleQuoteDTO;
import balance.sales.dto.SaleRequestDTO;
import balance.sales.dto.SaleResponseDTO;
import balance.service.FormsService;
import balance.tax.service.TaxService;
import balance.tenant.context.TenantContext;
import balance.tenant.db.TenantDataSourceConfig;
import balance.tenant.service.TenantConfigService;
import balance.users.service.PermissionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ventas y stock sin carreras, contra un Postgres 17 real con RLS activo: el backend se
 * conecta con el rol de aplicación (no dueño) y Flyway migra como dueño, igual que DEV.
 * Los servicios son los reales (ventas, inventario, turnos, impuestos, configuración);
 * solo se simulan los permisos (se prueban aparte) y el registro del cierre.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TenantDataSourceConfig.class, SalesService.class, InventoryService.class, ShiftService.class,
        TaxService.class, TenantConfigService.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VentasSinCarrerasTest {

    private static final String APP_ROLE = "test_app";
    private static final String APP_PASSWORD = "app_test_pw";
    private static final long TENANT = 1L;
    private static final long OTHER_TENANT = 2L;
    private static final long STORE = 10L;
    private static final long PRODUCT = 100L;

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        postgres.start();
        execAsOwner("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", () -> APP_ROLE);
        r.add("spring.datasource.password", () -> APP_PASSWORD);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "25");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired SalesService salesService;
    @Autowired ShiftService shiftService;
    @Autowired InventoryService inventoryService;
    @Autowired ObjectMapper objectMapper;

    @MockBean PermissionGuard permissionGuard;
    @MockBean FormsService formsService;
    @MockBean ShiftExpenseService shiftExpenseService;

    @BeforeEach
    void seed() {
        execAsOwner("TRUNCATE store, tenant_config, taxes RESTART IDENTITY CASCADE");
        execAsOwner("""
                INSERT INTO store (id, tenant_id, name, active) VALUES (10, 1, 'Central', true), (20, 2, 'Otra', true);
                INSERT INTO products (id, tenant_id, store_id, name, price, min_stock, active, type)
                    VALUES (100, 1, 10, 'Café', 10.00, 0, true, 'SIMPLE'),
                           (200, 2, 20, 'Té', 7.00, 0, true, 'SIMPLE');
                INSERT INTO tenant_config (tenant_id, currency, timezone) VALUES (1, 'ARS', 'America/Argentina/Buenos_Aires');
                """);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ── Stock atómico ────────────────────────────────────────────────────────

    @Test
    void veinteVentasEnParalelo_delUltimoItem_unaGanaYDiecinueveDan409_stockFinalCero() throws Exception {
        setStock(1);
        long shiftId = openShiftAsOwner(STORE, TENANT);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> TenantContext.runAs(TENANT, () -> salesService.createSale(shiftId, request(1))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).filteredOn(r -> r instanceof SaleResponseDTO).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof InsufficientStockException).hasSize(19);
        assertThat(results).filteredOn(r -> r instanceof Throwable && !(r instanceof InsufficientStockException))
                .as("ningún otro error (deadlock, 500, etc.)").isEmpty();
        assertThat(ownerCount("SELECT quantity FROM inventory_stock WHERE product_id = 100")).isZero();
        assertThat(ownerCount("SELECT count(*) FROM sales")).isEqualTo(1);
        assertThat(ownerCount("SELECT count(*) FROM inventory_movements WHERE source = 'SALE'")).isEqualTo(1);
    }

    @Test
    void entradasYSalidasEnParalelo_noPierdenActualizaciones() throws Exception {
        setStock(50);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            tasks.add(() -> TenantContext.runAs(TENANT, () -> inventoryService.adjust(STORE, adjustment("ENTRADA", 2))));
            tasks.add(() -> TenantContext.runAs(TENANT, () -> inventoryService.adjust(STORE, adjustment("SALIDA", 3))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).filteredOn(r -> r instanceof Throwable).isEmpty();
        assertThat(ownerCount("SELECT quantity FROM inventory_stock WHERE product_id = 100"))
                .isEqualTo(50 + 15 * 2 - 15 * 3);
    }

    @Test
    void ventaSinStock_noRegistraNada() {
        setStock(0);
        long shiftId = openShiftAsOwner(STORE, TENANT);

        TenantContext.setTenantId(TENANT);
        assertThatThrownBy(() -> salesService.createSale(shiftId, request(1)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Stock insuficiente");

        assertThat(ownerCount("SELECT count(*) FROM sales")).isZero();
        assertThat(ownerCount("SELECT count(*) FROM inventory_movements")).isZero();
    }

    // ── Turno único ──────────────────────────────────────────────────────────

    @Test
    void dosAperturasSimultaneas_danUnSoloTurno() throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String user = "cajero" + i;
            tasks.add(() -> TenantContext.runAs(TENANT, () -> shiftService.openShift(STORE, user)));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).filteredOn(r -> r instanceof ConflictException).hasSize(1)
                .first().extracting(r -> ((Throwable) r).getMessage())
                .isEqualTo("Ya hay un turno abierto en este local");
        assertThat(ownerCount("SELECT count(*) FROM shifts WHERE store_id = 10 AND status = 'OPEN'")).isEqualTo(1);
    }

    @Test
    void muchasAperturasSimultaneas_danUnSoloTurno() throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> TenantContext.runAs(TENANT, () -> shiftService.openShift(STORE, "cajero")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).filteredOn(r -> r instanceof ConflictException).hasSize(7);
        assertThat(ownerCount("SELECT count(*) FROM shifts WHERE status = 'OPEN'")).isEqualTo(1);
    }

    // ── Idempotencia ─────────────────────────────────────────────────────────

    @Test
    void mismaIdempotencyKey_tresVeces_unaSolaVenta_mismoCuerpo() throws Exception {
        setStock(10);
        long shiftId = openShiftAsOwner(STORE, TENANT);
        String key = java.util.UUID.randomUUID().toString(); // como el POS: un UUID por venta

        TenantContext.setTenantId(TENANT);
        IdempotentSaleResult first = salesService.createSaleIdempotent(shiftId, request(2), key);
        IdempotentSaleResult second = salesService.createSaleIdempotent(shiftId, request(2), key);
        IdempotentSaleResult third = salesService.createSaleIdempotent(shiftId, request(2), key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(third.replayed()).isTrue();
        String firstBody = objectMapper.writeValueAsString(first.body());
        assertThat(second.body()).isEqualTo(firstBody);
        assertThat(third.body()).isEqualTo(firstBody);
        assertThat(ownerCount("SELECT count(*) FROM sales")).isEqualTo(1);
        assertThat(ownerCount("SELECT quantity FROM inventory_stock WHERE product_id = 100")).isEqualTo(8);
    }

    @Test
    void mismaIdempotencyKey_tresVecesEnParalelo_unaSolaVenta() throws Exception {
        setStock(10);
        long shiftId = openShiftAsOwner(STORE, TENANT);
        String key = "paralelo-1";

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> TenantContext.runAs(TENANT, () -> salesService.createSaleIdempotent(shiftId, request(1), key)));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allMatch(r -> r instanceof IdempotentSaleResult);
        assertThat(results).filteredOn(r -> !((IdempotentSaleResult) r).replayed()).hasSize(1);
        assertThat(ownerCount("SELECT count(*) FROM sales")).isEqualTo(1);
        assertThat(ownerCount("SELECT quantity FROM inventory_stock WHERE product_id = 100")).isEqualTo(9);
    }

    @Test
    void mismaIdempotencyKey_conOtroContenido_da409() {
        setStock(10);
        long shiftId = openShiftAsOwner(STORE, TENANT);
        TenantContext.setTenantId(TENANT);
        salesService.createSaleIdempotent(shiftId, request(1), "k-1");

        assertThatThrownBy(() -> salesService.createSaleIdempotent(shiftId, request(3), "k-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThat(ownerCount("SELECT count(*) FROM sales")).isEqualTo(1);
    }

    @Test
    void ventaFallida_noConsumeLaIdempotencyKey() {
        setStock(0);
        long shiftId = openShiftAsOwner(STORE, TENANT);
        TenantContext.setTenantId(TENANT);

        assertThatThrownBy(() -> salesService.createSaleIdempotent(shiftId, request(1), "k-retry"))
                .isInstanceOf(InsufficientStockException.class);
        setStock(5);
        IdempotentSaleResult retry = salesService.createSaleIdempotent(shiftId, request(1), "k-retry");

        assertThat(retry.replayed()).isFalse();
        assertThat(ownerCount("SELECT count(*) FROM sales")).isEqualTo(1);
    }

    @Test
    void idempotencyKeyVencida_masDe24h_registraUnaVentaNueva() {
        setStock(10);
        long shiftId = openShiftAsOwner(STORE, TENANT);
        TenantContext.setTenantId(TENANT);
        salesService.createSaleIdempotent(shiftId, request(1), "k-vieja");
        execAsOwner("UPDATE idempotency_keys SET created_at = now() - interval '25 hours'");

        IdempotentSaleResult again = salesService.createSaleIdempotent(shiftId, request(1), "k-vieja");

        assertThat(again.replayed()).isFalse();
        assertThat(ownerCount("SELECT count(*) FROM sales")).isEqualTo(2);
        assertThat(ownerCount("SELECT count(*) FROM idempotency_keys")).isEqualTo(1);
    }

    @Test
    void laMismaIdempotencyKey_enOtraEmpresa_noDevuelveLaVentaAjena() {
        setStock(10);
        execAsOwner("INSERT INTO inventory_stock (tenant_id, product_id, store_id, quantity) VALUES (2, 200, 20, 10)");
        long shiftA = openShiftAsOwner(STORE, TENANT);
        long shiftB = openShiftAsOwner(20L, OTHER_TENANT);

        TenantContext.setTenantId(TENANT);
        IdempotentSaleResult a = salesService.createSaleIdempotent(shiftA, request(1), "misma-clave");
        TenantContext.setTenantId(OTHER_TENANT);
        SaleRequestDTO reqB = request(1);
        reqB.getItems().get(0).setProductId(200L);
        IdempotentSaleResult b = salesService.createSaleIdempotent(shiftB, reqB, "misma-clave");

        assertThat(b.replayed()).isFalse();
        assertThat(((SaleResponseDTO) b.body()).getId()).isNotEqualTo(((SaleResponseDTO) a.body()).getId());
        assertThat(((SaleResponseDTO) b.body()).getStoreId()).isEqualTo(20L);
        assertThat(ownerCount("SELECT count(*) FROM idempotency_keys")).isEqualTo(2);
    }

    // ── Pago MIXED y cotización ──────────────────────────────────────────────

    @Test
    void mixedQueNoCuadra_esRechazado_yNoRegistraNada() {
        setStock(10);
        long shiftId = openShiftAsOwner(STORE, TENANT);
        TenantContext.setTenantId(TENANT);
        SaleRequestDTO req = request(3); // 3 x 10.00 = 30.00
        req.setPaymentMethod("MIXED");
        req.setCashAmount(new BigDecimal("1.00"));
        req.setCardAmount(new BigDecimal("1.00"));

        assertThatThrownBy(() -> salesService.createSale(shiftId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no cuadra");
        assertThat(ownerCount("SELECT count(*) FROM sales")).isZero();
        assertThat(ownerCount("SELECT quantity FROM inventory_stock WHERE product_id = 100")).isEqualTo(10);
    }

    @Test
    void cotizacion_esIgualAlTotalCobrado_conImpuestoYRecargo() {
        setStock(10);
        execAsOwner("UPDATE tenant_config SET card_surcharge_rate = 0.0500 WHERE tenant_id = 1");
        execAsOwner("INSERT INTO taxes (tenant_id, name, rate, type, applies_to, active) "
                + "VALUES (1, 'IVA', 21.00, 'PERCENTAGE', 'ALL', true)");
        long shiftId = openShiftAsOwner(STORE, TENANT);
        TenantContext.setTenantId(TENANT);

        for (String method : List.of("CASH", "CARD", "MIXED")) {
            SaleRequestDTO req = request(3);
            req.setPaymentMethod(method);
            if ("MIXED".equals(method)) req.setCashAmount(new BigDecimal("10.00"));

            SaleQuoteDTO quote = salesService.quote(shiftId, req);
            SaleResponseDTO sale = salesService.createSale(shiftId, req);

            assertThat(sale.getTotal()).as(method).isEqualByComparingTo(quote.getTotal());
            assertThat(sale.getSubtotal()).as(method).isEqualByComparingTo(quote.getSubtotal());
            assertThat(sale.getIsv()).as(method).isEqualByComparingTo(quote.getTax());
            assertThat(sale.getCashAmount()).as(method).isEqualByComparingTo(quote.getCashAmount());
            assertThat(sale.getCardAmount()).as(method).isEqualByComparingTo(quote.getCardAmount());
        }
        // 30.00 + 21% = 36.30; CARD: 36.30 * 1.05 = 38.12 (redondeo a 2 decimales)
        assertThat(ownerDecimal("SELECT total FROM sales WHERE payment_method = 'CASH'")).isEqualByComparingTo("36.30");
        assertThat(ownerDecimal("SELECT total FROM sales WHERE payment_method = 'CARD'")).isEqualByComparingTo("38.12");
        // MIXED: 10.00 efectivo + 26.30 tarjeta * 1.05 = 27.62 -> 37.62
        assertThat(ownerDecimal("SELECT total FROM sales WHERE payment_method = 'MIXED'")).isEqualByComparingTo("37.62");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static SaleRequestDTO request(int quantity) {
        SaleItemRequestDTO item = new SaleItemRequestDTO();
        item.setProductId(PRODUCT);
        item.setQuantity(quantity);
        SaleRequestDTO req = new SaleRequestDTO();
        req.setUsername("cajero");
        req.setItems(new ArrayList<>(List.of(item)));
        req.setPaymentMethod("CASH");
        return req;
    }

    private static StockAdjustmentDTO adjustment(String type, int qty) {
        StockAdjustmentDTO dto = new StockAdjustmentDTO();
        dto.setProductId(PRODUCT);
        dto.setType(type);
        dto.setQuantity(qty);
        dto.setUsername("repositor");
        return dto;
    }

    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            try {
                results.add(f.get(60, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                results.add(e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                results.add(e);
            }
        }
        pool.shutdownNow();
        return results;
    }

    private static void setStock(int qty) {
        execAsOwner("INSERT INTO inventory_stock (tenant_id, product_id, store_id, quantity) VALUES (1, 100, 10, " + qty
                + ") ON CONFLICT (product_id, store_id) DO UPDATE SET quantity = EXCLUDED.quantity");
    }

    private static long openShiftAsOwner(long storeId, long tenantId) {
        execAsOwner("INSERT INTO shifts (tenant_id, store_id, code, status, username, opened_at) VALUES ("
                + tenantId + ", " + storeId + ", 'T-" + storeId + "', 'OPEN', 'cajero', now())");
        return ownerCount("SELECT id FROM shifts WHERE store_id = " + storeId + " AND status = 'OPEN'");
    }

    private static long ownerCount(String sql) {
        try (Connection c = ownerConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal ownerDecimal(String sql) {
        try (Connection c = ownerConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBigDecimal(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void execAsOwner(String sql) {
        try (Connection c = ownerConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
