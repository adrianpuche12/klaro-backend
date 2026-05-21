package balance.users.service;

import balance.common.enums.AppUserStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantContext;
import balance.users.dto.AppUserRequestDTO;
import balance.users.dto.AppUserResponseDTO;
import balance.users.model.AppUser;
import balance.users.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

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

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        // Simular llamada desde usuario con rol root (cumple con el guard de create())
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
        // role defaults to "user"
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

    // ── create — restricción de roles ─────────────────────────────────────────

    @Test
    void create_adminCannotCreateAdminRole() {
        // Simular caller como ADMIN (no root)
        var adminAuth = new TestingAuthenticationToken(
                "admin.user", null,
                List.of(new SimpleGrantedAuthority("ROLE_admin")));
        adminAuth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(adminAuth);

        AppUserRequestDTO dto = buildRequest("otro.admin", "Otro Admin", 1L);
        dto.setRole("admin");

        assertThatThrownBy(() -> appUserService.create(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Solo root puede crear");
    }

    @Test
    void create_rootCanCreateAdminRole() {
        when(userRepository.existsByUsernameAndTenantId("nuevo.admin", TENANT_ID)).thenReturn(false);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(keycloakAdmin.createUser(any(), any(), any(), eq("admin"), eq(TENANT_ID))).thenReturn("kc-uuid-admin");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUserRequestDTO dto = buildRequest("nuevo.admin", "Admin Nuevo", 1L);
        dto.setRole("admin");

        appUserService.create(dto);

        verify(keycloakAdmin).createUser(any(), any(), any(), eq("admin"), eq(TENANT_ID));
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

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removesUserFromKeycloakAndDatabase() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));

        appUserService.delete(1L);

        verify(keycloakAdmin).deleteUser("kc-uuid-1");
        verify(userRepository).delete(user);
    }

    @Test
    void delete_callsKeycloakBeforeDatabase() {
        AppUser user = buildUser(1L, "cajero01", AppUserStatus.ACTIVE);
        when(userRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(user));

        var inOrder = inOrder(keycloakAdmin, userRepository);
        appUserService.delete(1L);
        inOrder.verify(keycloakAdmin).deleteUser("kc-uuid-1");
        inOrder.verify(userRepository).delete(user);
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
}
