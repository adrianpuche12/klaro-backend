package balance.sales.service;

import balance.common.exception.ConflictException;
import balance.sales.dto.ShiftResponseDTO;
import balance.service.FormsService;
import balance.tenant.context.TenantContext;
import balance.tenant.db.TenantDataSourceConfig;
import balance.tenant.service.TenantConfigService;
import balance.users.service.PermissionGuard;
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

/**
 * Código de turno único POR EMPRESA (V16) y con correlativo del día, contra un Postgres 17 real
 * con RLS activo (rol de aplicación, Flyway como dueño), igual que {@link VentasSinCarrerasTest}.
 * Hallazgo de la Corrida k6 2 (08-Nov): el código era único en toda la base y dos empresas con
 * locales del mismo prefijo, o el mismo local reabriendo, chocaban en el mismo minuto (400).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TenantDataSourceConfig.class, ShiftService.class, TenantConfigService.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CodigoDeTurnoTest {

    private static final String APP_ROLE = "test_app";
    private static final String APP_PASSWORD = "app_test_pw";
    private static final long EMPRESA_A = 1L;
    private static final long EMPRESA_B = 2L;
    private static final long A_CENTRO = 10L;   // "Sucursal Centro" de la empresa A
    private static final long A_NORTE = 11L;    // "Sucursal Norte" de la empresa A (mismo prefijo SUC)
    private static final long B_CENTRO = 20L;   // "Sucursal Centro" de la empresa B (mismo nombre)

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
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired ShiftService shiftService;

    @MockBean PermissionGuard permissionGuard;
    @MockBean FormsService formsService;

    @BeforeEach
    void seed() {
        execAsOwner("TRUNCATE store, tenant_config RESTART IDENTITY CASCADE");
        execAsOwner("""
                INSERT INTO store (id, tenant_id, name, active) VALUES
                    (10, 1, 'Sucursal Centro', true), (11, 1, 'Sucursal Norte', true), (20, 2, 'Sucursal Centro', true);
                INSERT INTO tenant_config (tenant_id, currency, timezone) VALUES
                    (1, 'ARS', 'America/Argentina/Buenos_Aires'), (2, 'ARS', 'America/Argentina/Buenos_Aires');
                """);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void dosEmpresas_conLocalesDelMismoPrefijo_abrenEnElMismoMinuto_lasDosOk() throws Exception {
        List<Object> r = runConcurrently(List.of(
                () -> TenantContext.runAs(EMPRESA_A, () -> shiftService.openShift(A_CENTRO, "cajero.a")),
                () -> TenantContext.runAs(EMPRESA_B, () -> shiftService.openShift(B_CENTRO, "cajero.b"))));

        assertThat(r).allMatch(x -> x instanceof ShiftResponseDTO);
        assertThat(ownerCount("SELECT count(*) FROM shifts WHERE status = 'OPEN'")).isEqualTo(2);
    }

    @Test
    void mismaEmpresa_dosLocalesDelMismoPrefijo_abrenEnElMismoMinuto_codigosDistintos() throws Exception {
        List<Object> r = runConcurrently(List.of(
                () -> TenantContext.runAs(EMPRESA_A, () -> shiftService.openShift(A_CENTRO, "cajero1")),
                () -> TenantContext.runAs(EMPRESA_A, () -> shiftService.openShift(A_NORTE, "cajero2"))));

        assertThat(r).allMatch(x -> x instanceof ShiftResponseDTO);
        assertThat(r).extracting(x -> ((ShiftResponseDTO) x).getCode()).doesNotHaveDuplicates();
    }

    @Test
    void cerrarYReabrir_enElMismoMinuto_funciona_yElCorrelativoSube() {
        TenantContext.setTenantId(EMPRESA_A);
        ShiftResponseDTO primero = shiftService.openShift(A_CENTRO, "cajero1");
        shiftService.closeShift(primero.getId());
        ShiftResponseDTO segundo = shiftService.openShift(A_CENTRO, "cajero1");

        assertThat(primero.getCode()).endsWith("-SUC" + A_CENTRO + "-1");
        assertThat(segundo.getCode()).endsWith("-SUC" + A_CENTRO + "-2");
        assertThat(ownerCount("SELECT count(*) FROM shifts WHERE store_id = 10")).isEqualTo(2);
    }

    @Test
    void dosAperturasSimultaneas_enElMismoLocal_siguenDandoUnSoloTurno() throws Exception {
        List<Object> r = runConcurrently(List.of(
                () -> TenantContext.runAs(EMPRESA_A, () -> shiftService.openShift(A_CENTRO, "cajero1")),
                () -> TenantContext.runAs(EMPRESA_A, () -> shiftService.openShift(A_CENTRO, "cajero2"))));

        assertThat(r).filteredOn(x -> x instanceof ShiftResponseDTO).hasSize(1);
        assertThat(r).filteredOn(x -> x instanceof ConflictException).hasSize(1)
                .first().extracting(x -> ((Throwable) x).getMessage())
                .isEqualTo(ShiftService.SHIFT_ALREADY_OPEN);
        assertThat(ownerCount("SELECT count(*) FROM shifts WHERE store_id = 10 AND status = 'OPEN'")).isEqualTo(1);
    }

    @Test
    void elMismoCodigo_sePermiteEnOtraEmpresa_peroNoEnLaMisma() {
        execAsOwner("INSERT INTO shifts (tenant_id, store_id, code, status, username, opened_at) VALUES "
                + "(1, 10, 'T-X', 'CLOSED', 'a', now()), (2, 20, 'T-X', 'CLOSED', 'b', now())");
        assertThat(ownerCount("SELECT count(*) FROM shifts WHERE code = 'T-X'")).isEqualTo(2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> execAsOwner(
                        "INSERT INTO shifts (tenant_id, store_id, code, status, username, opened_at) VALUES "
                        + "(1, 11, 'T-X', 'CLOSED', 'c', now())"))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().hasMessageContaining("ux_shifts_tenant_code");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private static long ownerCount(String sql) {
        try (Connection c = ownerConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
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
