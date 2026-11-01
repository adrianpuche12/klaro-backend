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

/**
 * V11 sobre una base con datos previos (como DEV): las tablas hijas que reciben
 * tenant_id deben quedar con el tenant de su fila padre, no vacías ni cruzadas.
 */
@Testcontainers
class RowLevelSecurityMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void v11_backfillsTenantIdFromParentRows() throws SQLException {
        exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        flyway("10").migrate();

        exec("""
            INSERT INTO app_users (id, tenant_id, keycloak_id, username, full_name, status, created_at)
                VALUES (10, 1, 'kc-a', 'ana', 'Ana', 'ACTIVE', now()),
                       (20, 2, 'kc-b', 'beto', 'Beto', 'ACTIVE', now());
            INSERT INTO store (id, tenant_id, name, active) VALUES (100, 1, 'A', true), (200, 2, 'B', true);
            INSERT INTO user_permissions (user_id, permission) VALUES (10, 'POS'), (20, 'POS'), (20, 'INVENTORY');
            INSERT INTO user_store_access (user_id, store_id) VALUES (10, 100), (20, 200);
            INSERT INTO roles (id, tenant_id, name, level, can_manage_users) VALUES (1, 1, 'Cajero', 2, false), (2, 2, 'Cajero', 2, false);
            INSERT INTO role_permissions (role_id, permission) VALUES (1, 'POS'), (2, 'POS');
            """);

        flyway(null).migrate();

        assertThat(count("SELECT count(*) FROM user_permissions WHERE tenant_id = 1")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_permissions WHERE tenant_id = 2")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM user_store_access usa JOIN store s ON s.id = usa.store_id "
                + "WHERE usa.tenant_id <> s.tenant_id")).isZero();
        assertThat(count("SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                + "WHERE rp.tenant_id <> r.tenant_id")).isZero();
        assertThat(count("SELECT count(*) FROM role_permissions WHERE tenant_id IS NULL")).isZero();
    }

    /**
     * V12 sobre una base como DEV: la empresa 1 opera sin fila en tenants. La migración se la crea con slug
     * "tenant_1" (mismo prefijo que R2 ya usa) y el nombre de su configuración, y la secuencia sigue después.
     */
    @Test
    void v12_createsMissingTenantRows_andAdvancesSequence() throws SQLException {
        exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        flyway("11").migrate();
        exec("""
            INSERT INTO store (id, tenant_id, name, active) VALUES (10, 1, 'Local', true), (11, 1, 'Otro', true);
            INSERT INTO tenant_config (tenant_id, company_name, currency, timezone) VALUES (1, 'Mi Empresa', 'USD', 'America/Lima');
            """);

        flyway(null).migrate();

        assertThat(count("SELECT count(*) FROM tenants WHERE id = 1 AND slug = 'tenant_1' AND name = 'Mi Empresa' AND active")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tenants")).isEqualTo(1);
        exec("INSERT INTO tenants (slug, name, plan, active) VALUES ('nueva', 'Nueva', 'starter', true)");
        assertThat(count("SELECT id FROM tenants WHERE slug = 'nueva'")).isEqualTo(2);
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
