package balance.users.service;

import balance.common.enums.AppUserStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantContext;
import balance.users.model.AppUser;
import balance.users.model.PermissionModule;
import balance.users.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionGuardTest {

    private static final Long TENANT_A = 1L;
    private static final Long TENANT_B = 2L;
    private static final String KEYCLOAK_ID = "kc-uuid-test";

    @InjectMocks private PermissionGuard permissionGuard;

    @Mock private AppUserRepository userRepository;
    @Mock private StoreRepository   storeRepository;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void authenticateAs(String keycloakId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", keycloakId)
                .subject(keycloakId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        var auth = new TestingAuthenticationToken(jwt, null);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Store buildStore(Long id, Long tenantId) {
        Store s = new Store();
        s.setId(id);
        s.setName("Local " + id);
        s.setTenantId(tenantId);
        return s;
    }

    private AppUser legacyUser() {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setKeycloakId(KEYCLOAK_ID);
        u.setTenantId(TENANT_A);
        u.setStatus(AppUserStatus.ACTIVE);
        // sin businessRole, sin permissions, sin accessibleStores -> legacy
        return u;
    }

    private AppUser restrictedUser(Set<String> permissions, Set<Store> stores) {
        AppUser u = new AppUser();
        u.setId(2L);
        u.setKeycloakId(KEYCLOAK_ID);
        u.setTenantId(TENANT_A);
        u.setStatus(AppUserStatus.ACTIVE);
        u.setBusinessRole("CONTADOR");
        u.setPermissions(permissions);
        u.setAccessibleStores(stores);
        return u;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Usuario legacy (creado antes de SPRINT-09) — acceso total, sin cambios
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void legacyUser_hasFullAccess_toAnyModuleAndStore() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(legacyUser()));

        assertThatCode(() -> permissionGuard.assertAccess(PermissionModule.INVENTORY, 99L))
                .doesNotThrowAnyException();
        verifyNoInteractions(storeRepository); // ni siquiera necesita resolver el store
    }

    @Test
    void legacyUser_hasFullAccess_toModuleOnlyCheck() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(legacyUser()));

        assertThatCode(() -> permissionGuard.assertAccess(PermissionModule.DASHBOARD))
                .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Usuario con perfil acotado — módulo
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void restrictedUser_deniedModule_notInPermissions() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        AppUser user = restrictedUser(Set.of("DASHBOARD"), Set.of(store));
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A)).thenReturn(Optional.of(user));
        when(storeRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(store));

        assertThatThrownBy(() -> permissionGuard.assertAccess(PermissionModule.INVENTORY, 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void restrictedUser_allowedModule_inPermissions() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        AppUser user = restrictedUser(Set.of("INVENTORY"), Set.of(store));
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A)).thenReturn(Optional.of(user));
        when(storeRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(store));

        assertThatCode(() -> permissionGuard.assertAccess(PermissionModule.INVENTORY, 1L))
                .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Usuario con perfil acotado — local
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void restrictedUser_deniedStore_notInAccessibleStores() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store allowedStore = buildStore(1L, TENANT_A);
        Store otherStore   = buildStore(2L, TENANT_A);
        AppUser user = restrictedUser(Set.of("INVENTORY"), Set.of(allowedStore));
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A)).thenReturn(Optional.of(user));
        when(storeRepository.findByIdAndTenantId(2L, TENANT_A)).thenReturn(Optional.of(otherStore));

        assertThatThrownBy(() -> permissionGuard.assertAccess(PermissionModule.INVENTORY, 2L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void restrictedUser_deniedWhenStoreIdOmitted_aggregateQuery() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        AppUser user = restrictedUser(Set.of("OPERATIONS"), Set.of(store));
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> permissionGuard.assertAccess(PermissionModule.OPERATIONS, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("local");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Aislamiento multi-tenant — el hueco que PH v2 nunca tuvo que cerrar
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void crossTenantStoreId_isDenied_evenIfInAccessibleStores() {
        // Caso patológico: si por un bug de asignación una store de otro tenant
        // terminara en accessibleStores, el guard igual la rechaza porque
        // resuelve el store contra el tenant del caller primero.
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store foreignStore = buildStore(99L, TENANT_B);
        AppUser user = restrictedUser(Set.of("INVENTORY"), Set.of(foreignStore));
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A)).thenReturn(Optional.of(user));
        // El store 99 pertenece a TENANT_B -> no existe bajo TENANT_A
        when(storeRepository.findByIdAndTenantId(99L, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionGuard.assertAccess(PermissionModule.INVENTORY, 99L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noAppUserRowForKeycloakId_hasFullAccess_notDenied() {
        // Hoy es el caso de TODOS los usuarios reales de Belopia: la migración a
        // Contabo (SPRINT-08C) partió de un schema vacío, sin datos migrados.
        // Antes de SPRINT-09 nada autorizaba contra app_users -> negar acá sería
        // una regresión real para cuentas ya en uso, no una mejora de seguridad.
        TenantContext.setTenantId(TENANT_A);
        authenticateAs("kc-uuid-sin-fila-en-app-users");
        when(userRepository.findByKeycloakIdAndTenantId("kc-uuid-sin-fila-en-app-users", TENANT_A))
                .thenReturn(Optional.empty());

        assertThatCode(() -> permissionGuard.assertAccess(PermissionModule.INVENTORY, 1L))
                .doesNotThrowAnyException();
        verifyNoInteractions(storeRepository);
    }

    @Test
    void noTenantContext_throwsSecurityException() {
        TenantContext.clear();
        authenticateAs(KEYCLOAK_ID);

        assertThatThrownBy(() -> permissionGuard.assertAccess(PermissionModule.INVENTORY, 1L))
                .isInstanceOf(SecurityException.class);
    }
}
