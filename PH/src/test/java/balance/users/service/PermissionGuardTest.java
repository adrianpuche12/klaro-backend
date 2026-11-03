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
    void noAppUserRowForKeycloakId_isDenied_failClosed() {
        // Etapa 1 (P6): antes una cuenta sin fila en app_users tenía acceso total ("legacy").
        // Ahora falla cerrado: sin fila en la empresa del token no hay acceso a nada.
        // En DEV las únicas cuentas sin fila son root.klaro (root, pasa explícito) y
        // nilo.plataforma (plataforma, tenant 0).
        TenantContext.setTenantId(TENANT_A);
        authenticateAs("kc-uuid-sin-fila-en-app-users");
        when(userRepository.findByKeycloakIdAndTenantId("kc-uuid-sin-fila-en-app-users", TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionGuard.assertAccess(PermissionModule.INVENTORY, 1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> permissionGuard.assertAccess(PermissionModule.DASHBOARD))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> permissionGuard.assertAccessAny(PermissionModule.TRANSACTIONS))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> permissionGuard.assertManage(PermissionModule.OPERATIONS, 1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> permissionGuard.shiftReadScope(1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> permissionGuard.storeScope())
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(storeRepository);
    }

    @Test
    void suspendedUser_isDenied_evenWithValidToken() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        AppUser suspended = legacyUser();
        suspended.setStatus(AppUserStatus.SUSPENDED);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> permissionGuard.assertAccess(PermissionModule.DASHBOARD))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Root pasa siempre, de forma explícita
    // ═══════════════════════════════════════════════════════════════════════════

    private void authenticateAsRoot(String keycloakId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(keycloakId).claim("preferred_username", "root.klaro")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var auth = new TestingAuthenticationToken(jwt, null, "ROLE_root");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void root_withoutRow_passesEverything() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsRoot(KEYCLOAK_ID);

        assertThatCode(() -> {
            permissionGuard.assertAccess(PermissionModule.INVENTORY, 1L);
            permissionGuard.assertAccess(PermissionModule.DASHBOARD);
            permissionGuard.assertManage(PermissionModule.OPERATIONS, 1L);
            permissionGuard.assertStoreAllowed(1L);
        }).doesNotThrowAnyException();
        assertThat(permissionGuard.shiftReadScope(1L)).isEqualTo(PermissionGuard.ShiftReadScope.ALL);
        assertThat(permissionGuard.storeScope().unrestricted()).isTrue();
        assertThat(permissionGuard.canManageUsers()).isTrue();
        verifyNoInteractions(userRepository, storeRepository);
    }

    @Test
    void root_withRoleAndStoresAssigned_stillPasses() {
        // Antes root pasaba solo porque su fila no tenía Role ni locales ("legacy"): si alguien
        // le asignaba un Role acotado o un local, quedaba bloqueado. Ahora el rol de realm manda.
        TenantContext.setTenantId(TENANT_A);
        authenticateAsRoot(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        lenient().when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("POS"), false, Set.of(store))));

        assertThatCode(() -> {
            permissionGuard.assertAccess(PermissionModule.INVENTORY, 99L);
            permissionGuard.assertAccessAny(PermissionModule.TRANSACTIONS);
            permissionGuard.assertManage(PermissionModule.CATALOG, 99L);
        }).doesNotThrowAnyException();
        assertThat(permissionGuard.storeScope().allows(99L)).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Alcance de locales para listados (P2/P7/P10)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void storeScope_restrictedUser_onlyHisStores_andNeverRecordsWithoutStore() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store mine = buildStore(1L, TENANT_A);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("OPERATIONS"), false, Set.of(mine))));

        PermissionGuard.StoreScope scope = permissionGuard.storeScope();

        assertThat(scope.unrestricted()).isFalse();
        assertThat(scope.allows(1L)).isTrue();
        assertThat(scope.allows(2L)).isFalse();
        assertThat(scope.allows((Long) null)).isFalse();
    }

    @Test
    void storeScope_userWithoutProfile_isUnrestricted() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A)).thenReturn(Optional.of(legacyUser()));

        assertThat(permissionGuard.storeScope().unrestricted()).isTrue();
    }

    @Test
    void storeScope_platformAdmin_isEmpty_notForbidden() {
        // nilo.plataforma (tenant 0, sin fila): su panel no se rompe, pero no ve ningún local.
        TenantContext.setTenantId(0L);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("kc-plataforma")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var auth = new TestingAuthenticationToken(jwt, null, "ROLE_platform");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        PermissionGuard.StoreScope scope = permissionGuard.storeScope();

        assertThat(scope.unrestricted()).isFalse();
        assertThat(scope.storeIds()).isEmpty();
        verifyNoInteractions(userRepository);
    }

    @Test
    void assertStoreAllowed_storeOutsideHisStores_isDenied() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store mine = buildStore(1L, TENANT_A);
        Store other = buildStore(2L, TENANT_A);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("POS"), false, Set.of(mine))));
        when(storeRepository.findByIdAndTenantId(2L, TENANT_A)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> permissionGuard.assertStoreAllowed(2L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void canManageUsers_onlyRootOrManagerRole_neverWithoutRow() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(userWithRole(Set.of("POS"), false, Set.of())))
                .thenReturn(Optional.of(userWithRole(Set.of("POS"), true, Set.of())));

        assertThat(permissionGuard.canManageUsers()).isFalse();
        assertThat(permissionGuard.canManageUsers()).isFalse();
        assertThat(permissionGuard.canManageUsers()).isTrue();
    }

    @Test
    void noTenantContext_throwsSecurityException() {
        TenantContext.clear();
        authenticateAs(KEYCLOAK_ID);

        assertThatThrownBy(() -> permissionGuard.assertAccess(PermissionModule.INVENTORY, 1L))
                .isInstanceOf(SecurityException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Alcance de lectura de turnos (un cajero nunca ve turnos de otro)
    // ═══════════════════════════════════════════════════════════════════════════

    private AppUser userWithRole(Set<String> permissions, boolean canManageUsers, Set<Store> stores) {
        AppUser u = new AppUser();
        u.setId(3L);
        u.setKeycloakId(KEYCLOAK_ID);
        u.setTenantId(TENANT_A);
        u.setStatus(AppUserStatus.ACTIVE);
        balance.users.model.Role role = new balance.users.model.Role();
        role.setPermissions(new java.util.HashSet<>(permissions));
        role.setCanManageUsers(canManageUsers);
        u.setRole(role);
        u.setAccessibleStores(stores);
        return u;
    }

    @Test
    void shiftScope_legacyUser_seesAll() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A)).thenReturn(Optional.of(legacyUser()));

        assertThat(permissionGuard.shiftReadScope(1L)).isEqualTo(PermissionGuard.ShiftReadScope.ALL);
    }

    @Test
    void shiftScope_managerWithSalesHistory_seesAll() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("SALES_HISTORY", "POS"), true, Set.of(store))));
        when(storeRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(store));

        assertThat(permissionGuard.shiftReadScope(1L)).isEqualTo(PermissionGuard.ShiftReadScope.ALL);
    }

    @Test
    void shiftScope_cashierWithSalesHistory_seesOnlyOwn() {
        // Caso real de DEV: el rol "Cajero" tiene POS + SALES_HISTORY y no gestiona usuarios.
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("SALES_HISTORY", "POS"), false, Set.of(store))));
        when(storeRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(store));

        assertThat(permissionGuard.shiftReadScope(1L)).isEqualTo(PermissionGuard.ShiftReadScope.OWN);
    }

    @Test
    void shiftScope_withoutPosOrSalesHistory_isDenied() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("INVENTORY", "CATALOG"), false, Set.of(store))));
        when(storeRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(store));

        assertThatThrownBy(() -> permissionGuard.shiftReadScope(1L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shiftScope_storeNotAccessible_isDenied() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store mine = buildStore(1L, TENANT_A);
        Store other = buildStore(2L, TENANT_A);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("SALES_HISTORY", "POS"), true, Set.of(mine))));
        when(storeRepository.findByIdAndTenantId(2L, TENANT_A)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> permissionGuard.shiftReadScope(2L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void currentUsername_comesFromTheToken() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(KEYCLOAK_ID).claim("preferred_username", "test.cajero")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var auth = new TestingAuthenticationToken(jwt, null);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(permissionGuard.currentUsername()).isEqualTo("test.cajero");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // assertManage: escritura de encargado (catálogo, salidas/ajustes de stock)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void manage_staffWithModuleButNotManager_isDenied() {
        // Caso real de DEV: "contador" tiene CATALOG e INVENTORY pero no gestiona usuarios.
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("CATALOG", "INVENTORY"), false, Set.of(store))));
        when(storeRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(store));

        assertThatThrownBy(() -> permissionGuard.assertManage(PermissionModule.CATALOG, 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void manage_managerWithModule_isAllowed() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("CATALOG"), true, Set.of(store))));
        when(storeRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(store));

        assertThatCode(() -> permissionGuard.assertManage(PermissionModule.CATALOG, 1L)).doesNotThrowAnyException();
    }

    @Test
    void manage_managerWithoutModule_isDenied() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        Store store = buildStore(1L, TENANT_A);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(userWithRole(Set.of("POS"), true, Set.of(store))));
        when(storeRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(store));

        assertThatThrownBy(() -> permissionGuard.assertManage(PermissionModule.CATALOG, 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void manage_legacyUser_isAllowed() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAs(KEYCLOAK_ID);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A)).thenReturn(Optional.of(legacyUser()));

        assertThatCode(() -> permissionGuard.assertManage(PermissionModule.INVENTORY, 1L)).doesNotThrowAnyException();
    }
}
