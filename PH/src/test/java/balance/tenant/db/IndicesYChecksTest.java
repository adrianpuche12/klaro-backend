package balance.tenant.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V14 contra un Postgres 17 real: un turno abierto por local, CHECKs de stock/montos,
 * índices de los listados (EXPLAIN con el rol de aplicación y RLS activo) e índice en
 * toda FK.
 */
@Testcontainers
class IndicesYChecksTest {

    private static final String APP_ROLE = "test_app";
    private static final String APP_PASSWORD = "app_test_pw";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrate() throws SQLException {
        execAsOwner("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clean() throws SQLException {
        execAsOwner("TRUNCATE store, products, shifts, sales, sale_items, inventory_stock RESTART IDENTITY CASCADE");
        execAsOwner("""
                INSERT INTO store (id, tenant_id, name, active) VALUES (1, 1, 'Local A', true), (2, 1, 'Local B', true);
                INSERT INTO products (id, tenant_id, store_id, name, price, min_stock, active, type)
                    VALUES (1, 1, 1, 'Café', 10.00, 0, true, 'SIMPLE');
                """);
    }

    // ── Un turno abierto por local ───────────────────────────────────────────

    @Test
    void segundoTurnoAbierto_enElMismoLocal_falla() throws SQLException {
        insertShift(1, "T1", "OPEN");

        assertThatThrownBy(() -> insertShift(1, "T2", "OPEN"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ux_shift_open")
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
    }

    @Test
    void turnoAbierto_enOtroLocal_oTurnosCerrados_sePermiten() throws SQLException {
        insertShift(1, "T1", "OPEN");
        insertShift(2, "T2", "OPEN");
        insertShift(1, "T3", "CLOSED");
        insertShift(1, "T4", "CLOSED");

        assertThat(count("SELECT count(*) FROM shifts")).isEqualTo(4);
    }

    @Test
    void cerrarYReabrir_enElMismoLocal_sePermite() throws SQLException {
        insertShift(1, "T1", "OPEN");
        execAsOwner("UPDATE shifts SET status = 'CLOSED' WHERE code = 'T1'");
        insertShift(1, "T2", "OPEN");

        assertThat(count("SELECT count(*) FROM shifts WHERE status = 'OPEN'")).isEqualTo(1);
    }

    // ── CHECKs ───────────────────────────────────────────────────────────────

    @Test
    void stockNegativo_esRechazado() throws SQLException {
        assertThatThrownBy(() -> execAsOwner(
                "INSERT INTO inventory_stock (tenant_id, product_id, store_id, quantity) VALUES (1, 1, 1, -1)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("inventory_stock_quantity_non_negative");

        execAsOwner("INSERT INTO inventory_stock (tenant_id, product_id, store_id, quantity) VALUES (1, 1, 1, 0)");
        assertThatThrownBy(() -> execAsOwner("UPDATE inventory_stock SET quantity = quantity - 1"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }

    @Test
    void ventaConTotalNegativo_esRechazada() throws SQLException {
        insertShift(1, "T1", "OPEN");
        assertThatThrownBy(() -> execAsOwner("""
                INSERT INTO sales (tenant_id, store_id, shift_id, sale_date, subtotal, isv, total, status, username)
                VALUES (1, 1, 1, current_date, 10, 0, -1, 'OPEN', 'ana')
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("sales_total_non_negative");
    }

    @Test
    void precioNegativo_esRechazado() {
        assertThatThrownBy(() -> execAsOwner("UPDATE products SET price = -1 WHERE id = 1"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("products_price_non_negative");
    }

    @Test
    void checksDeV14_quedanValidados_enUnaBaseLimpia() throws SQLException {
        assertThat(count("""
                SELECT count(*) FROM pg_constraint
                WHERE conname IN ('inventory_stock_quantity_non_negative', 'sales_total_non_negative',
                                  'sales_amounts_non_negative', 'sale_items_amounts_non_negative',
                                  'products_price_non_negative', 'products_min_stock_non_negative',
                                  'inventory_movements_quantity_positive', 'taxes_rate_non_negative',
                                  'tenant_config_card_surcharge_non_negative')
                  AND convalidated
                """)).isEqualTo(9);
    }

    // ── Índices ──────────────────────────────────────────────────────────────

    @Test
    void listadoDeVentasPorLocal_usaElIndice_conRlsActivo() throws SQLException {
        // 3 empresas x 2 locales x 1 turno x 500 ventas, para que el plan sea realista.
        execAsOwner("""
                INSERT INTO store (id, tenant_id, name, active)
                    SELECT g, (g % 3) + 1, 'Local ' || g, true FROM generate_series(10, 15) g;
                INSERT INTO shifts (tenant_id, store_id, code, status, username, opened_at)
                    SELECT tenant_id, id, 'S' || id, 'CLOSED', 'ana', now() FROM store WHERE id >= 10;
                INSERT INTO sales (tenant_id, store_id, shift_id, sale_date, subtotal, isv, total, status, username, created_at)
                    SELECT sh.tenant_id, sh.store_id, sh.id, current_date, 10, 0, 10, 'CONFIRMED', 'ana',
                           now() - (g || ' minutes')::interval
                    FROM shifts sh CROSS JOIN generate_series(1, 500) g
                    WHERE sh.store_id >= 10;
                ANALYZE sales;
                """);
        long tenantOfStore10 = (10 % 3) + 1;

        String plan;
        try (Connection c = appConnection(tenantOfStore10); Statement st = c.createStatement()) {
            st.execute("SET enable_seqscan = off");
            // Mismo filtro y orden que SaleRepository.findByStoreIdAndTenantIdAndDateRange.
            plan = explain(st, "SELECT * FROM sales WHERE store_id = 10 AND tenant_id = " + tenantOfStore10
                    + " AND sale_date >= current_date - 30 AND sale_date <= current_date ORDER BY created_at DESC");
        }

        assertThat(plan).contains("idx_sales_tenant_store_created");
    }

    @Test
    void todaFk_tieneIndice() throws SQLException {
        List<String> sinIndice = new ArrayList<>();
        try (Connection c = ownerConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT con.conrelid::regclass || '.' || con.conname
                     FROM pg_constraint con
                     JOIN pg_namespace n ON n.oid = con.connamespace
                     WHERE con.contype = 'f' AND n.nspname = 'public'
                       AND NOT EXISTS (
                           SELECT 1 FROM pg_index i
                           WHERE i.indrelid = con.conrelid AND i.indpred IS NULL
                             AND (string_to_array(i.indkey::text, ' ')::int2[])[1:cardinality(con.conkey)] = con.conkey)
                     """)) {
            while (rs.next()) sinIndice.add(rs.getString(1));
        }
        assertThat(sinIndice).as("FKs sin índice (agregarlo en la migración que crea la FK)").isEmpty();
    }

    @Test
    void indicesDeListados_existen() throws SQLException {
        assertThat(count("""
                SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname IN (
                    'idx_sales_tenant_store_created', 'idx_shifts_tenant_store_opened',
                    'idx_transactions_tenant_store_date', 'idx_inventory_movements_tenant_store_created',
                    'idx_bank_deposits_tenant_store_date', 'ux_shift_open')
                """)).isEqualTo(6);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void insertShift(long storeId, String code, String status) throws SQLException {
        execAsOwner("INSERT INTO shifts (tenant_id, store_id, code, status, username, opened_at) VALUES (1, "
                + storeId + ", '" + code + "', '" + status + "', 'ana', now())");
    }

    private static String explain(Statement st, String sql) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) sb.append(rs.getString(1)).append('\n');
        }
        return sb.toString();
    }

    private static Connection appConnection(long tenantId) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
        try (Statement st = c.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
        }
        return c;
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = ownerConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void execAsOwner(String sql) throws SQLException {
        try (Connection c = ownerConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
