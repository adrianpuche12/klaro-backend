package balance.tenant.db;

import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantContext;
import balance.tenant.dto.TenantOnboardingRequestDTO;
import balance.tenant.model.Tenant;
import balance.tenant.repository.TenantConfigRepository;
import balance.tenant.repository.TenantRepository;
import balance.tenant.service.TenantOnboardingService;
import balance.users.model.AppUser;
import balance.users.repository.AppUserRepository;
import balance.users.service.KeycloakAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPRINT-16 contra Postgres 17 real, app conectada como rol de aplicación (sujeto a RLS):
 * el administrador de plataforma (tenant 0) ve y crea empresas, pero NUNCA sus datos de negocio,
 * y el alta completa deja cada fila en el tenant correcto (o se deshace entera).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TenantDataSourceConfig.class, TenantOnboardingService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlatformAdminRlsTest {

    private static final String APP_ROLE = "test_app";
    private static final String APP_PASSWORD = "app_test_pw";
    private static final long PLATFORM = TenantContext.PLATFORM_TENANT_ID;

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
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration");
    }

    @Autowired TenantOnboardingService onboardingService;
    @Autowired TenantRepository tenantRepository;
    @Autowired TenantConfigRepository tenantConfigRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired StoreRepository storeRepository;
    @MockBean KeycloakAdminService keycloakAdminService;

    @BeforeEach
    void clean() {
        execAsOwner("TRUNCATE app_users, store, tenant_config, tenants RESTART IDENTITY CASCADE");
        execAsOwner("INSERT INTO tenants (id, slug, name, plan, active, created_at) VALUES "
                + "(1, 'empresa-a', 'Empresa A', 'starter', true, now()), (2, 'empresa-b', 'Empresa B', 'starter', true, now())");
        execAsOwner("SELECT setval('tenants_id_seq', 2)");
        execAsOwner("INSERT INTO store (tenant_id, name, active) VALUES (1, 'Local A', true), (2, 'Local B', true)");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ── Qué ve la plataforma ────────────────────────────────────────────────

    @Test
    void platform_seesAllTenants() {
        TenantContext.setTenantId(PLATFORM);
        assertThat(tenantRepository.findAll()).extracting(Tenant::getSlug).containsExactlyInAnyOrder("empresa-a", "empresa-b");
    }

    @Test
    void platform_neverSeesBusinessData() {
        TenantContext.setTenantId(PLATFORM);
        assertThat(storeRepository.findAll()).isEmpty();
        assertThat(appUserRepository.findAll()).isEmpty();
    }

    @Test
    void platform_cannotWriteBusinessDataOfATenant() {
        TenantContext.setTenantId(PLATFORM);
        Store forged = new Store();
        forged.setName("Intruso");
        forged.setTenantId(1L);
        assertThatThrownBy(() -> storeRepository.saveAndFlush(forged))
                .hasRootCauseMessage("ERROR: new row violates row-level security policy for table \"store\"");
    }

    @Test
    void tenant_onlySeesItsOwnTenantRow() {
        TenantContext.setTenantId(1L);
        assertThat(tenantRepository.findAll()).extracting(Tenant::getSlug).containsExactly("empresa-a");
        assertThat(tenantRepository.findById(2L)).isEmpty();
    }

    // ── Alta completa ───────────────────────────────────────────────────────

    @Test
    void onboarding_createsTenantConfigAndRootUser_eachInTheRightTenant() {
        when(keycloakAdminService.createUser(eq("dueno.nuevo"), anyString(), anyString(), eq("root"), anyLong()))
                .thenReturn("kc-nuevo");
        TenantContext.setTenantId(PLATFORM);

        TenantOnboardingService.OnboardingResult result = onboardingService.onboard(request("panaderia-sol", "dueno.nuevo"));
        long newId = result.tenant().getId();

        assertThat(newId).isEqualTo(3L);
        verify(keycloakAdminService).createUser("dueno.nuevo", "Dueño Nuevo", "Clave-Segura-1", "root", 3L);
        // La plataforma ve la empresa y su configuración...
        assertThat(tenantRepository.findById(newId)).get().extracting(Tenant::getSlug).isEqualTo("panaderia-sol");
        assertThat(tenantConfigRepository.findById(newId)).get()
                .satisfies(c -> {
                    assertThat(c.getCurrency()).isEqualTo("USD");
                    assertThat(c.getTimezone()).isEqualTo("America/Bogota");
                });
        // ...pero no el usuario dueño, que es dato de la empresa.
        assertThat(appUserRepository.findAll()).isEmpty();

        // La empresa nueva ve a su dueño, y nada de las otras empresas.
        TenantContext.setTenantId(newId);
        assertThat(appUserRepository.findAll()).extracting(AppUser::getUsername).containsExactly("dueno.nuevo");
        assertThat(storeRepository.findAll()).isEmpty();
        assertThat(tenantRepository.findAll()).extracting(Tenant::getSlug).containsExactly("panaderia-sol");
    }

    @Test
    void onboarding_failureAfterKeycloak_undoesEverything() throws SQLException {
        // Un usuario con ese username ya existe en OTRA empresa: la fila de app_users choca con el UNIQUE global.
        execAsOwner("INSERT INTO app_users (tenant_id, keycloak_id, username, full_name, status, created_at) "
                + "VALUES (1, 'kc-a', 'repetido', 'Ya Existe', 'ACTIVE', now())");
        when(keycloakAdminService.createUser(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn("kc-huerfano");
        TenantContext.setTenantId(PLATFORM);

        assertThatThrownBy(() -> onboardingService.onboard(request("tienda-luna", "repetido")))
                .isInstanceOf(RuntimeException.class);

        verify(keycloakAdminService).deleteUser("kc-huerfano");
        TenantContext.setTenantId(PLATFORM);
        assertThat(tenantRepository.findAll()).extracting(Tenant::getSlug).doesNotContain("tienda-luna");
        assertThat(ownerCount("SELECT count(*) FROM tenant_config")).isZero();
    }

    @Test
    void onboarding_rejectsDuplicateSlug_withoutTouchingKeycloak() {
        TenantContext.setTenantId(PLATFORM);
        assertThatThrownBy(() -> onboardingService.onboard(request("empresa-a", "otro.dueno")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empresa-a");
        verify(keycloakAdminService, never()).createUser(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void onboarding_rejectsInvalidTimezone() {
        TenantOnboardingRequestDTO dto = request("tienda-x", "x.dueno");
        dto.setTimezone("Marte/Olympus");
        TenantContext.setTenantId(PLATFORM);
        assertThatThrownBy(() -> onboardingService.onboard(dto)).isInstanceOf(IllegalArgumentException.class);
        verify(keycloakAdminService, never()).createUser(anyString(), anyString(), anyString(), anyString(), any());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static TenantOnboardingRequestDTO request(String slug, String rootUsername) {
        TenantOnboardingRequestDTO dto = new TenantOnboardingRequestDTO();
        dto.setName("Empresa " + slug);
        dto.setSlug(slug);
        dto.setCurrency("usd");
        dto.setTimezone("America/Bogota");
        dto.setRootUsername(rootUsername);
        dto.setRootFullName("Dueño Nuevo");
        dto.setRootPassword("Clave-Segura-1");
        return dto;
    }

    private static long ownerCount(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execAsOwner(String sql) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
