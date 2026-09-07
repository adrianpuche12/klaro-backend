package balance.users.service;

import balance.common.enums.AppUserStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantContext;
import balance.users.dto.AppUserRequestDTO;
import balance.users.dto.AppUserResponseDTO;
import balance.users.model.AppUser;
import balance.users.model.Role;
import balance.users.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppUserServiceTest {

    private static final Long TENANT_ID = 1L;

    @InjectMocks private AppUserService appUserService;

    @Mock private AppUserRepository    userRepository;
    @Mock private StoreRepository      storeRepository;
    @Mock private KeycloakAdminService keycloakAdmin;
    @Mock private RoleService          roleService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        // Simular llamada desde usuario con rol root -- assertCanManage está
        // mockeado (no-op por defecto en Mockito para métodos void), así que
        // el rol de Keycloak acá no filtra nada por sí solo; se deja por
        // consistencia con TenantIsolationTest y otros tests del módulo.
        var auth = new TestingAuthenticationToken(
                "admin.klaro", null,
                List.of(new SimpleGrantedAuthority("ROLE_root")));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id, String name) {
        Store s = new Store();
        s.setId(id);
        s.setName(name);
        s.setTenantId(TENANT_ID);
        return s;
    }

    private Role buildRole(Long id, String name, int level) {
        Role r = new Role();
        r.setId(id);
        r.setTenantId(TENANT_ID);
        r.setName(name);
        r.setLevel(level);
        return r;
    }

    private AppUser buildUser(Long id, String username, AppUserStatus status) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setFullName("Empleado Test");
        u.setKeycloakId("kc-uuid-" + id);
        u.setStatus(status);
        u.setStore(buildStore(1L, "Danli"));
        u.setTenantId(TENANT_ID);
        return u;
    }

    private AppUserRequestDTO buildRequest(String username, String fullName, Long storeId) {
        AppUserRequestDTO dto = new AppUserRequestDTO();
        dto.setUsername(username);
        dto.setFullName(fullName);
        dto.setPassword("pass123");
        dto.setStoreId(storeId);
        return dto;
    }

    // ── create — normalización ────────────────────────────────────────────────

    @Test
    void create_normalizesUsernameToLowercase() {
        when(userRepository.existsByUsernameAndTenantId("cajero01", TENANT_ID)).thenReturn(false);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn("kc-uuid-nuevo");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.create(buildRequest("CAJERO01", "Cajero Uno", 1L));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("cajero01");
    }

    @Test
    void create_trimesUsernameWhitespace() {
        when(userRepository.existsByUsernameAndTenantId("cajero01", TENANT_ID)).thenReturn(false);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn("kc-uuid-nuevo");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.create(buildRequest("  cajero01  ", "Cajero Uno", 1L));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("cajero01");
    }

    @Test
    void create_savesKeycloakIdReturnedByKeycloak() {
        when(userRepository.existsByUsernameAndTenantId("cajero01", TENANT_ID)).thenReturn(false);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn("kc-uuid-abc123");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.create(buildRequest("cajero01", "Cajero", 1L));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getKeycloakId()).isEqualTo("kc-uuid-abc123");
    }

    @Test
    void create_setsStatusToActiveByDefault() {
        when(userRepository.existsByUsernameAndTenantId("cajero01", TENANT_ID)).thenReturn(false);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn("kc-uuid-nuevo");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.create(buildRequest("cajero01", "Cajero", 1L));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AppUserStatus.ACTIVE);
    }

    // ── create — SPRINT-14: siempre "staff" en Keycloak, cascada vía RoleService ─

    @Test
    void create_alwaysUsesStaffKeycloakRole_regardlessOfRoleId() {
        Role targetRole = buildRole(5L, "Encargado", 1);
        when(userRepository.existsByUsernameAndTenantId("carlos", TENANT_ID)).thenReturn(false);
        when(roleService.findOrThrow(5L, TENANT_ID)).thenReturn(targetRole);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn("kc-uuid-nuevo");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserRequestDTO dto = buildRequest("carlos", "Carlos", 1L);
        dto.setRoleId(5L);

        appUserService.create(dto);

        verify(keycloakAdmin).createUser(any(), any(), any(), eq("staff"), eq(TENANT_ID));
        verify(roleService).assertCanManage(targetRole);
    }

    @Test
    void create_withNullRoleId_callsAssertCanManageWithNull() {
        when(userRepository.existsByUsernameAndTenantId("cajero01", TENANT_ID)).thenReturn(false);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn("kc-uuid-nuevo");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.create(buildRequest("cajero01", "Cajero", 1L));

        verify(roleService).assertCanManage(null);
    }

    @Test
    void create_propagatesAccessDeniedFromRoleService() {
        Role targetRole = buildRole(5L, "Encargado", 1);
        when(roleService.findOrThrow(5L, TENANT_ID)).thenReturn(targetRole);
        doThrow(new AccessDeniedException("No podés crear o gestionar un usuario de tu mismo nivel o superior"))
                .when(roleService).assertCanManage(targetRole);

        AppUserRequestDTO dto = buildRequest("carlos", "Carlos", 1L);
        dto.setRoleId(5L);

        assertThatThrownBy(() -> appUserService.create(dto))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(keycloakAdmin);
    }

    // ── create — validaciones ─────────────────────────────────────────────────

    @Test
    void create_throwsWhenUsernameAlreadyExists() {
        when(userRepository.existsByUsernameAndTenantId("cajero01", TENANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> appUserService.create(buildRequest("cajero01", "Cajero", 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya esta en uso");

        verifyNoInteractions(keycloakAdmin);
    }

    @Test
    void create_throwsWhenStoreNotFound() {
        when(userRepository.existsByUsernameAndTenantId("cajero01", TENANT_ID)).thenReturn(false);
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appUserService.create(buildRequest("cajero01", "Cajero", 99L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local no encontrado");

        verifyNoInteractions(keycloakAdmin);
    }

    // ── suspend ───────────────────────────────────────────────────────────────

    @Test
    void suspend_changesStatusToSuspended() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserResponseDTO result = appUserService.suspend(1L);

        assertThat(result.getStatus()).isEqualTo("SUSPENDED");
    }

    @Test
    void suspend_disablesUserInKeycloak() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.suspend(1L);

        verify(keycloakAdmin).setUserEnabled("kc-uuid-1", false);
    }

    @Test
    void suspend_throwsWhenUserAlreadySuspended() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.SUSPENDED);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> appUserService.suspend(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya está suspendido");
    }

    @Test
    void suspend_throwsWhenUserNotFound() {
        when(userRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appUserService.suspend(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usuario no encontrado");
    }

    @Test
    void suspend_propagatesAccessDeniedFromRoleService() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        doThrow(new AccessDeniedException("denegado")).when(roleService).assertCanManage(user.getRole());

        assertThatThrownBy(() -> appUserService.suspend(1L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(keycloakAdmin);
    }

    // ── activate ──────────────────────────────────────────────────────────────

    @Test
    void activate_changesStatusToActive() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.SUSPENDED);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserResponseDTO result = appUserService.activate(1L);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void activate_enablesUserInKeycloak() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.SUSPENDED);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.activate(1L);

        verify(keycloakAdmin).setUserEnabled("kc-uuid-1", true);
    }

    @Test
    void activate_throwsWhenUserAlreadyActive() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> appUserService.activate(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya está activo");
    }

    // ── reassign ──────────────────────────────────────────────────────────────

    @Test
    void reassign_changesUserStore() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        Store newStore = buildStore(2L, "El Paraíso");

        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(storeRepository.findByIdAndTenantId(2L, TENANT_ID)).thenReturn(Optional.of(newStore));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.reassign(1L, 2L);

        assertThat(user.getStore().getId()).isEqualTo(2L);
    }

    @Test
    void reassign_throwsWhenNewStoreNotFound() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appUserService.reassign(1L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local no encontrado");
    }

    // ── delete (soft-delete, SPRINT-14) ──────────────────────────────────────

    @Test
    void delete_setsStatusToDeleted_neverRemovesRow() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.delete(1L);

        assertThat(user.getStatus()).isEqualTo(AppUserStatus.DELETED);
        verify(userRepository, never()).delete(any());
        verify(userRepository).save(user);
    }

    @Test
    void delete_disablesUserInKeycloak() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.delete(1L);

        verify(keycloakAdmin).setUserEnabled("kc-uuid-1", false);
    }

    @Test
    void delete_propagatesAccessDeniedFromRoleService() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        doThrow(new AccessDeniedException("denegado")).when(roleService).assertCanManage(user.getRole());

        assertThatThrownBy(() -> appUserService.delete(1L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(keycloakAdmin);
        verify(userRepository, never()).save(any());
    }

    // ── findAll / findByStore — gate de lectura (SPRINT-14) ──────────────────

    @Test
    void findAll_callsAssertCanManageUsers() {
        when(userRepository.findByTenantIdOrderByFullNameAsc(TENANT_ID)).thenReturn(List.of());

        appUserService.findAll();

        verify(roleService).assertCanManageUsers();
    }

    @Test
    void findAll_propagatesAccessDeniedFromRoleService() {
        doThrow(new AccessDeniedException("denegado")).when(roleService).assertCanManageUsers();

        assertThatThrownBy(() -> appUserService.findAll()).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(userRepository);
    }

    // ── findByUsername ────────────────────────────────────────────────────────

    @Test
    void findByUsername_normalizesToLowercase() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByUsernameAndTenantId("cajero01", TENANT_ID)).thenReturn(Optional.of(user));

        appUserService.findByUsername("CAJERO01");

        verify(userRepository).findByUsernameAndTenantId("cajero01", TENANT_ID);
    }

    @Test
    void findByUsername_throwsWhenNotFound() {
        when(userRepository.findByUsernameAndTenantId("desconocido", TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appUserService.findByUsername("desconocido"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usuario no encontrado");
    }

    // ── create — perfil acotado (SPRINT-09/14) ───────────────────────────────

    @Test
    void create_allowsNullStoreId_forRestrictedProfile() {
        when(userRepository.existsByUsernameAndTenantId("contador01", TENANT_ID)).thenReturn(false);
        when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn("kc-uuid-nuevo");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserRequestDTO dto = buildRequest("contador01", "Contador Uno", null);

        AppUserResponseDTO result = appUserService.create(dto);

        assertThat(result.getStoreId()).isNull();
        verify(storeRepository, never()).findByIdAndTenantId(any(), any());
    }

    @Test
    void create_savesRoleAndAccessibleStores() {
        Role contadorRole = buildRole(9L, "Contador", 1);
        when(userRepository.existsByUsernameAndTenantId("contador01", TENANT_ID)).thenReturn(false);
        when(roleService.findOrThrow(9L, TENANT_ID)).thenReturn(contadorRole);
        when(storeRepository.findByIdAndTenantId(2L, TENANT_ID)).thenReturn(Optional.of(buildStore(2L, "El Paraiso")));
        when(storeRepository.findByIdAndTenantId(3L, TENANT_ID)).thenReturn(Optional.of(buildStore(3L, "Danli 2")));
        when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn("kc-uuid-nuevo");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserRequestDTO dto = buildRequest("contador01", "Contador Uno", null);
        dto.setRoleId(9L);
        dto.setStoreIds(List.of(2L, 3L));

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        appUserService.create(dto);
        verify(userRepository).save(captor.capture());

        assertThat(captor.getValue().getRole()).isEqualTo(contadorRole);
        assertThat(captor.getValue().getAccessibleStores()).extracting("id").containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    void create_throwsWhenAccessibleStoreBelongsToAnotherTenant() {
        when(userRepository.existsByUsernameAndTenantId("contador01", TENANT_ID)).thenReturn(false);
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        AppUserRequestDTO dto = buildRequest("contador01", "Contador Uno", null);
        dto.setStoreIds(List.of(99L));

        assertThatThrownBy(() -> appUserService.create(dto))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(keycloakAdmin);
    }

    // ── updateRole (SPRINT-14) ───────────────────────────────────────────────

    @Test
    void updateRole_assignsRoleAfterCascadeCheck() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        Role role = buildRole(9L, "Contador", 1);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(roleService.findOrThrow(9L, TENANT_ID)).thenReturn(role);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.updateRole(1L, 9L);

        verify(roleService).assertCanManage(role);
        assertThat(user.getRole()).isEqualTo(role);
    }

    @Test
    void updateRole_propagatesAccessDeniedFromRoleService() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        Role role = buildRole(9L, "Contador", 1);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(roleService.findOrThrow(9L, TENANT_ID)).thenReturn(role);
        doThrow(new AccessDeniedException("denegado")).when(roleService).assertCanManage(role);

        assertThatThrownBy(() -> appUserService.updateRole(1L, 9L)).isInstanceOf(AccessDeniedException.class);
        verify(userRepository, never()).save(any());
    }

    // ── updatePermissions (deprecado, SPRINT-09) ─────────────────────────────

    @Test
    void updatePermissions_replacesPermissionSet() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserResponseDTO result = appUserService.updatePermissions(1L, List.of("INVENTORY", "CATALOG"));

        assertThat(result.getPermissions()).containsExactlyInAnyOrder("INVENTORY", "CATALOG");
    }

    @Test
    void updatePermissions_emptyListMeansNoAccess() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        user.setPermissions(new java.util.HashSet<>(Set.of("DASHBOARD")));
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        appUserService.updatePermissions(1L, null);

        assertThat(user.getPermissions()).isEmpty();
    }

    // ── updateStoreAccess ─────────────────────────────────────────────────────

    @Test
    void updateStoreAccess_replacesAccessibleStores() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        when(storeRepository.findByIdAndTenantId(2L, TENANT_ID)).thenReturn(Optional.of(buildStore(2L, "El Paraiso")));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserResponseDTO result = appUserService.updateStoreAccess(1L, List.of(2L));

        assertThat(result.getAccessibleStoreIds()).containsExactly(2L);
    }

    @Test
    void updateStoreAccess_throwsWhenStoreBelongsToAnotherTenant() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));
        // Store 77 pertenece a otro tenant -> no existe bajo TENANT_ID
        when(storeRepository.findByIdAndTenantId(77L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appUserService.updateStoreAccess(1L, List.of(77L)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }
}
