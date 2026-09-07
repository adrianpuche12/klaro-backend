package balance.users.service;

import balance.tenant.context.TenantContext;
import balance.users.dto.RoleRequestDTO;
import balance.users.dto.RoleResponseDTO;
import balance.users.model.AppUser;
import balance.users.model.Role;
import balance.users.repository.AppUserRepository;
import balance.users.repository.RoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    private static final Long TENANT_A = 1L;
    private static final String KEYCLOAK_ID = "kc-uuid-caller";

    @InjectMocks private RoleService roleService;

    @Mock private RoleRepository    roleRepository;
    @Mock private AppUserRepository userRepository;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void authenticateAsRoot() {
        var auth = new TestingAuthenticationToken(
                "root.klaro", null, List.of(new SimpleGrantedAuthority("ROLE_root")));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateAsStaff() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", KEYCLOAK_ID)
                .subject(KEYCLOAK_ID)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        var auth = new TestingAuthenticationToken(jwt, null, List.of(new SimpleGrantedAuthority("ROLE_staff")));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Role buildRole(Long id, String name, int level, boolean canManageUsers) {
        Role r = new Role();
        r.setId(id);
        r.setTenantId(TENANT_A);
        r.setName(name);
        r.setLevel(level);
        r.setCanManageUsers(canManageUsers);
        return r;
    }

    private AppUser buildCaller(Role role) {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setTenantId(TENANT_A);
        u.setKeycloakId(KEYCLOAK_ID);
        u.setRole(role);
        return u;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // assertCanManage — la cascada (Regla 1 del diseño)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void root_canManageAnyRole_evenLevelOne() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsRoot();
        Role level1 = buildRole(1L, "Admin", 1, true);

        assertThatCode(() -> roleService.assertCanManage(level1)).doesNotThrowAnyException();
        verifyNoInteractions(userRepository); // root no necesita resolver su propio Role
    }

    @Test
    void root_canManageNullRole_createLegacyUser() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsRoot();

        assertThatCode(() -> roleService.assertCanManage(null)).doesNotThrowAnyException();
    }

    @Test
    void staffWithoutCanManageUsers_cannotManageAnyone() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsStaff();
        Role callerRole = buildRole(2L, "Cajero", 2, false);
        Role targetRole = buildRole(3L, "Otro Cajero", 3, false);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(buildCaller(callerRole)));

        assertThatThrownBy(() -> roleService.assertCanManage(targetRole))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffWithCanManageUsers_canManageLowerLevel() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsStaff();
        Role callerRole = buildRole(1L, "Admin", 1, true);
        Role targetRole = buildRole(2L, "Cajero", 2, false);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(buildCaller(callerRole)));

        assertThatCode(() -> roleService.assertCanManage(targetRole)).doesNotThrowAnyException();
    }

    @Test
    void staffWithCanManageUsers_cannotManageSameLevel() {
        // Un "Admin" (nivel 1, canManageUsers) intenta crear a otro "Admin" (nivel 1)
        TenantContext.setTenantId(TENANT_A);
        authenticateAsStaff();
        Role callerRole = buildRole(1L, "Admin", 1, true);
        Role targetRole = buildRole(4L, "Otro Admin", 1, true);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(buildCaller(callerRole)));

        assertThatThrownBy(() -> roleService.assertCanManage(targetRole))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("mismo nivel o superior");
    }

    @Test
    void staffWithCanManageUsers_cannotManageHigherLevel() {
        // Un "Encargado" (nivel 2) no puede gestionar un "Admin" (nivel 1, más alto en la jerarquía)
        TenantContext.setTenantId(TENANT_A);
        authenticateAsStaff();
        Role callerRole = buildRole(2L, "Encargado", 2, true);
        Role targetRole = buildRole(1L, "Admin", 1, true);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(buildCaller(callerRole)));

        assertThatThrownBy(() -> roleService.assertCanManage(targetRole))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffWithCanManageUsers_cannotCreateLegacyUser() {
        // targetRole == null (usuario sin Role, acceso total) -- solo root puede
        TenantContext.setTenantId(TENANT_A);
        authenticateAsStaff();
        Role callerRole = buildRole(1L, "Admin", 1, true);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(buildCaller(callerRole)));

        assertThatThrownBy(() -> roleService.assertCanManage(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffWithoutAppUserRow_isDenied() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsStaff();
        Role targetRole = buildRole(2L, "Cajero", 2, false);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.assertCanManage(targetRole))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // assertCanManageUsers — gate de lectura (listar usuarios)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void assertCanManageUsers_rootAlwaysAllowed() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsRoot();
        assertThatCode(() -> roleService.assertCanManageUsers()).doesNotThrowAnyException();
    }

    @Test
    void assertCanManageUsers_deniedForRoleWithoutFlag() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsStaff();
        Role callerRole = buildRole(2L, "Cajero", 2, false);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(buildCaller(callerRole)));

        assertThatThrownBy(() -> roleService.assertCanManageUsers())
                .isInstanceOf(AccessDeniedException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // findAll — root ve todo, un manager solo ve Roles de nivel inferior asignable
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findAll_rootSeesEveryRole() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsRoot();
        when(roleRepository.findByTenantIdOrderByLevelAscNameAsc(TENANT_A))
                .thenReturn(List.of(buildRole(1L, "Admin", 1, true), buildRole(2L, "Cajero", 2, false)));

        List<RoleResponseDTO> result = roleService.findAll();

        assertThat(result).hasSize(2);
    }

    @Test
    void findAll_managerOnlySeesRolesBelowOwnLevel() {
        TenantContext.setTenantId(TENANT_A);
        authenticateAsStaff();
        Role callerRole = buildRole(1L, "Admin", 1, true);
        when(userRepository.findByKeycloakIdAndTenantId(KEYCLOAK_ID, TENANT_A))
                .thenReturn(Optional.of(buildCaller(callerRole)));
        when(roleRepository.findByTenantIdOrderByLevelAscNameAsc(TENANT_A))
                .thenReturn(List.of(
                        buildRole(1L, "Admin", 1, true),      // su propio nivel -- no debe verlo
                        buildRole(2L, "Encargado", 2, true),  // nivel inferior -- sí
                        buildRole(3L, "Cajero", 3, false)));  // nivel inferior -- sí

        List<RoleResponseDTO> result = roleService.findAll();

        assertThat(result).extracting("name").containsExactlyInAnyOrder("Encargado", "Cajero");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // create / delete
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void create_throwsWhenNameAlreadyExistsInTenant() {
        TenantContext.setTenantId(TENANT_A);
        when(roleRepository.existsByTenantIdAndName(TENANT_A, "Admin")).thenReturn(true);

        RoleRequestDTO dto = new RoleRequestDTO();
        dto.setName("Admin");
        dto.setLevel(1);

        assertThatThrownBy(() -> roleService.create(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ya existe");
    }

    @Test
    void delete_throwsWhenUsersStillAssigned() {
        TenantContext.setTenantId(TENANT_A);
        Role role = buildRole(1L, "Cajero", 2, false);
        when(roleRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(role));
        when(userRepository.existsByRoleId(1L)).thenReturn(true);

        assertThatThrownBy(() -> roleService.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reasignalos");

        verify(roleRepository, never()).delete(any());
    }

    @Test
    void delete_removesRoleWhenNoUsersAssigned() {
        TenantContext.setTenantId(TENANT_A);
        Role role = buildRole(1L, "Cajero", 2, false);
        when(roleRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(role));
        when(userRepository.existsByRoleId(1L)).thenReturn(false);

        roleService.delete(1L);

        verify(roleRepository).delete(role);
    }
}
