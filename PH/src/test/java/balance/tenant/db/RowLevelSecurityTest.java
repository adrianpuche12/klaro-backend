package balance.tenant.db;

import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
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
 * Verifica el aislamiento multi-tenant a nivel base de datos (V11, Row Level
 * Security) contra un Postgres 17 real, con el backend conectado como rol de
 * aplicación (no dueño, no superusuario) y Flyway migrando como dueño — la
 * misma separación de roles que DEV/PROD.
 *
 * Las consultas usan a propósito métodos SIN filtro de tenant (findAll,
 * SELECT sin WHERE): lo que se prueba es que Postgres aísla aunque el código
 * se olvide de filtrar.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TenantDataSourceConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RowLevelSecurityTest {

    // V11 deriva el rol del nombre de la base: "test" -> "test_app".
    private static final String APP_ROLE = "test_app";
    private static final String APP_PASSWORD = "app_test_pw";
    private static final long TENANT_A = 1L;
    private static final long TENANT_B = 2L;

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        postgres.start();
        // Paso operativo previo al deploy: el rol existe con LOGIN antes de que
        // V11 corra (V11 lo crea NOLOGIN solo si no existe).
        execAsOwner("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", () -> APP_ROLE);
        r.add("spring.datasource.password", () -> APP_PASSWORD);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration");
    }

    @Autowired StoreRepository storeRepository;
    @Autowired DataSource dataSource;

    @BeforeEach
    void cleanAsOwner() {
        execAsOwner("TRUNCATE store, role_permissions, roles RESTART IDENTITY CASCADE");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ── Garantías del modelo de roles ────────────────────────────────────────

    @Test
    void appRole_isNotOwnerNorSuperuserNorBypassRls() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("rolsuper")).isFalse();
            assertThat(rs.getBoolean("rolbypassrls")).isFalse();
        }
    }

    @Test
    void everyTableWithTenantId_hasRlsEnabledAndPolicy() throws SQLException {
        List<String> unprotected = new ArrayList<>();
        try (Connection c = ownerConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT cl.relname
                     FROM pg_class cl
                     JOIN pg_namespace n ON n.oid = cl.relnamespace
                     JOIN pg_attribute a ON a.attrelid = cl.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
                     WHERE n.nspname = 'public' AND cl.relkind = 'r'
                       AND (NOT cl.relrowsecurity
                            OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = cl.oid))
                     """)) {
            while (rs.next()) unprotected.add(rs.getString(1));
        }
        assertThat(unprotected)
                .as("Toda tabla con tenant_id necesita RLS + política (agregarla en su migración)")
                .isEmpty();
    }

    @Test
    void appRole_cannotTouchFlywayHistory() {
        TenantContext.setTenantId(TENANT_A);
        assertThatThrownBy(() -> queryCount("SELECT count(*) FROM flyway_schema_history"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    // ── Aislamiento de lectura/escritura vía JPA ─────────────────────────────

    @Test
    void unfilteredFindAll_onlyReturnsCurrentTenantRows() {
        saveStoreAs(TENANT_A, "Local A");
        saveStoreAs(TENANT_B, "Local B");

        TenantContext.setTenantId(TENANT_A);
        assertThat(storeRepository.findAll()).extracting(Store::getName).containsExactly("Local A");

        TenantContext.setTenantId(TENANT_B);
        assertThat(storeRepository.findAll()).extracting(Store::getName).containsExactly("Local B");
    }

    @Test
    void findById_ofOtherTenantRow_isNotFound() {
        Store a = saveStoreAs(TENANT_A, "Local A");

        TenantContext.setTenantId(TENANT_B);
        assertThat(storeRepository.findById(a.getId())).isEmpty();
    }

    @Test
    void withoutTenantInSession_noRowsAreVisible() {
        saveStoreAs(TENANT_A, "Local A");

        TenantContext.clear();
        assertThat(storeRepository.findAll()).isEmpty();
    }

    @Test
    void cannotInsertRowForAnotherTenant() {
        TenantContext.setTenantId(TENANT_B);
        Store forged = new Store();
        forged.setName("Intruso");
        forged.setTenantId(TENANT_A);

        assertThatThrownBy(() -> storeRepository.saveAndFlush(forged))
                .hasRootCauseMessage(rootMessageOfRlsViolation());
    }

    @Test
    void cannotUpdateOrDeleteOtherTenantRows() throws SQLException {
        Store a = saveStoreAs(TENANT_A, "Local A");

        TenantContext.setTenantId(TENANT_B);
        assertThat(executeUpdate("UPDATE store SET name = 'pisado' WHERE id = " + a.getId())).isZero();
        assertThat(executeUpdate("DELETE FROM store WHERE id = " + a.getId())).isZero();

        TenantContext.setTenantId(TENANT_A);
        assertThat(storeRepository.findById(a.getId())).get()
                .extracting(Store::getName).isEqualTo("Local A");
    }

    // ── Tablas hijas que recibieron tenant_id en V11 ─────────────────────────

    @Test
    void childTableWithoutExplicitTenant_takesTenantFromSession_andIsIsolated() throws SQLException {
        TenantContext.setTenantId(TENANT_A);
        executeUpdate("INSERT INTO roles (tenant_id, name, level, can_manage_users) VALUES (1, 'Cajero', 2, false)");
        // Como hace JPA con @ElementCollection: sin mencionar tenant_id.
        executeUpdate("INSERT INTO role_permissions (role_id, permission) "
                + "SELECT id, 'POS' FROM roles WHERE name = 'Cajero'");

        assertThat(queryCount("SELECT count(*) FROM role_permissions WHERE tenant_id = 1")).isEqualTo(1);

        TenantContext.setTenantId(TENANT_B);
        assertThat(queryCount("SELECT count(*) FROM role_permissions")).isZero();
        assertThat(queryCount("SELECT count(*) FROM roles")).isZero();
    }

    @Test
    void childTableInsertWithoutTenantInSession_isRejected() {
        TenantContext.clear();
        assertThatThrownBy(() -> executeUpdate("INSERT INTO role_permissions (role_id, permission) VALUES (999, 'POS')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void pooledConnection_neverKeepsPreviousTenant() {
        saveStoreAs(TENANT_A, "Local A");

        // Muchos checkouts alternados sobre el mismo pool.
        for (int i = 0; i < 20; i++) {
            TenantContext.setTenantId(TENANT_A);
            assertThat(storeRepository.findAll()).hasSize(1);
            TenantContext.setTenantId(TENANT_B);
            assertThat(storeRepository.findAll()).isEmpty();
            TenantContext.clear();
            assertThat(storeRepository.findAll()).isEmpty();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Store saveStoreAs(long tenantId, String name) {
        TenantContext.setTenantId(tenantId);
        Store s = new Store();
        s.setName(name);
        s.setTenantId(tenantId);
        Store saved = storeRepository.saveAndFlush(s);
        TenantContext.clear();
        return saved;
    }

    private int executeUpdate(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            return st.executeUpdate(sql);
        }
    }

    private long queryCount(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String rootMessageOfRlsViolation() {
        return "ERROR: new row violates row-level security policy for table \"store\"";
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
