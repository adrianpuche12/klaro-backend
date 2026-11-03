package balance.tenant.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guardia de arranque de RLS contra un Postgres 17 real, con la misma separación de roles
 * que DEV: una base "guard" cuyo dueño (guard_owner, NO superusuario, como belopia_owner)
 * corre Flyway, y el rol de aplicación guard_app que crea V11.
 */
@Testcontainers
class DatabaseRoleGuardTest {

    private static final String DB = "guard";
    private static final String OWNER = "guard_owner";
    private static final String APP = "guard_app";        // V11: current_database() || '_app'
    private static final String BYPASS = "guard_bypass";
    private static final String PW = "pw";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrateAsNonSuperuserOwner() throws SQLException {
        execAsSuperuser("CREATE ROLE " + OWNER + " LOGIN NOSUPERUSER PASSWORD '" + PW + "'");
        execAsSuperuser("CREATE ROLE " + APP + " LOGIN PASSWORD '" + PW + "'");
        execAsSuperuser("CREATE ROLE " + BYPASS + " LOGIN BYPASSRLS PASSWORD '" + PW + "'");
        execAsSuperuser("CREATE DATABASE " + DB + " OWNER " + OWNER);
        Flyway.configure()
                .dataSource(urlOf(DB), OWNER, PW)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // V11 revoca CONNECT a PUBLIC: el rol con BYPASSRLS necesita permiso explícito.
        execAsSuperuserOn(DB, "GRANT CONNECT ON DATABASE " + DB + " TO " + BYPASS);
    }

    // ── Perfiles exigidos (dev/prod): el backend no arranca ─────────────────

    @Test
    void dev_conectadoComoDuenio_noArranca() {
        DatabaseRoleGuard guard = guard("dev", OWNER, OWNER);

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO arranca")
                .hasMessageContaining("dueño")
                .hasMessageContaining("mismo usuario");
    }

    @Test
    void dev_duenioAunqueDbMigrationUserApunteAOtro_noArranca() {
        // DB_USER = dueño por error, aunque DB_MIGRATION_USER esté seteado con otro nombre.
        DatabaseRoleGuard guard = guard("dev", OWNER, "otro_usuario");

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("es dueño");
    }

    @Test
    void prod_conectadoComoSuperusuario_noArranca() {
        MockEnvironment env = env("prod", postgres.getUsername(), OWNER);
        DataSource ds = new DriverManagerDataSource(urlOf(DB), postgres.getUsername(), postgres.getPassword());

        assertThatThrownBy(() -> new DatabaseRoleGuard(ds, env).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPERUSER");
    }

    @Test
    void dev_rolConBypassRls_noArranca() {
        DatabaseRoleGuard guard = guard("dev", BYPASS, OWNER);

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BYPASSRLS");
    }

    @Test
    void dev_sinDbMigrationUser_noArranca() {
        DatabaseRoleGuard guard = guard("dev", APP, null);

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_MIGRATION_USER");
    }

    @Test
    void dev_rolDeAplicacion_arranca() {
        // Configuración real de DEV: app = belopia_dev_app, Flyway = belopia_owner.
        assertThatCode(() -> guard("dev", APP, OWNER).verify()).doesNotThrowAnyException();
    }

    @Test
    void prod_rolDeAplicacion_arranca() {
        assertThatCode(() -> guard("prod", APP, OWNER).verify()).doesNotThrowAnyException();
    }

    // ── Perfiles no exigidos: solo warning ───────────────────────────────────

    @Test
    void local_conectadoComoDuenio_soloAvisa() {
        assertThatCode(() -> guard("local", OWNER, null).verify()).doesNotThrowAnyException();
    }

    @Test
    void test_conectadoComoDuenio_soloAvisa() {
        assertThatCode(() -> guard("test", OWNER, OWNER).verify()).doesNotThrowAnyException();
    }

    // ── El arranque del contexto de Spring falla de verdad ───────────────────

    @Test
    void contextoSpring_perfilDevConDuenio_fallaAlArrancar() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withBean(DataSource.class, () -> new DriverManagerDataSource(urlOf(DB), OWNER, PW))
                .withPropertyValues("spring.datasource.username=" + OWNER, "DB_MIGRATION_USER=" + OWNER)
                .withUserConfiguration(DatabaseRoleGuard.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Guardia de RLS");
                });
    }

    @Test
    void contextoSpring_perfilDevConRolDeAplicacion_arranca() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withBean(DataSource.class, () -> new DriverManagerDataSource(urlOf(DB), APP, PW))
                .withPropertyValues("spring.datasource.username=" + APP, "DB_MIGRATION_USER=" + OWNER)
                .withUserConfiguration(DatabaseRoleGuard.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DatabaseRoleGuard guard(String profile, String connectAs, String migrationUser) {
        DataSource ds = new DriverManagerDataSource(urlOf(DB), connectAs, PW);
        return new DatabaseRoleGuard(ds, env(profile, connectAs, migrationUser));
    }

    private static MockEnvironment env(String profile, String appUser, String migrationUser) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        env.setProperty("spring.datasource.username", appUser);
        if (migrationUser != null) env.setProperty("DB_MIGRATION_USER", migrationUser);
        return env;
    }

    private static String urlOf(String database) {
        return postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    private static void execAsSuperuser(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static void execAsSuperuserOn(String database, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(urlOf(database), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
