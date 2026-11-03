package balance.tenant.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V14 sobre una base con datos previos (como DEV/PROD): los CHECKs verificados se validan
 * y frenan la migración si hay datos rotos; los no verificados quedan NOT VALID con un
 * warning en vez de frenar el deploy.
 */
@Testcontainers
class IndicesYChecksMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void checkNoVerificado_conDatosViejosQueNoCumplen_quedaNotValid_yLaMigracionSigue() throws SQLException {
        resetTo("12");
        exec("""
            INSERT INTO store (id, tenant_id, name, active) VALUES (1, 1, 'A', true);
            INSERT INTO products (id, tenant_id, store_id, name, price, min_stock, active, type)
                VALUES (1, 1, 1, 'Viejo', -5.00, 0, true, 'SIMPLE');
            """);

        flyway(null).migrate();

        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname = 'products_price_non_negative' AND NOT convalidated"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname = 'inventory_stock_quantity_non_negative' AND convalidated"))
                .isEqualTo(1);
        // Igual rige para filas nuevas.
        assertThatThrownBy(() -> exec("INSERT INTO products (tenant_id, store_id, name, price, min_stock, active, type) "
                + "VALUES (1, 1, 'Nuevo', -1, 0, true, 'SIMPLE')"))
                .hasMessageContaining("products_price_non_negative");
    }

    @Test
    void stockNegativoPrevio_frenaLaMigracion() throws SQLException {
        resetTo("12");
        exec("""
            INSERT INTO store (id, tenant_id, name, active) VALUES (1, 1, 'A', true);
            INSERT INTO products (id, tenant_id, store_id, name, price, min_stock, active, type)
                VALUES (1, 1, 1, 'P', 1.00, 0, true, 'SIMPLE');
            INSERT INTO inventory_stock (tenant_id, product_id, store_id, quantity) VALUES (1, 1, 1, -3);
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
                .hasStackTraceContaining("inventory_stock_quantity_non_negative");
    }

    private static void resetTo(String target) throws SQLException {
        exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        flyway(target).migrate();
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
