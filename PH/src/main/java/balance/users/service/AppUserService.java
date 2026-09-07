package balance.users.service;

import balance.common.enums.AppUserStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import balance.users.dto.AppUserRequestDTO;
import balance.users.dto.AppUserResponseDTO;
import balance.users.model.AppUser;
import balance.users.model.Role;
import balance.users.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AppUserService {

    /** Único rol de Keycloak para cualquier cuenta no-root creada a través de
     * esta app (SPRINT-14) — la granularidad real vive en el Role asignado
     * (tabla `roles`), no en Keycloak. Root se provisiona fuera de este flujo. */
    private static final String STAFF_KEYCLOAK_ROLE = "staff";

    @Autowired private AppUserRepository    userRepository;
    @Autowired private StoreRepository      storeRepository;
    @Autowired private KeycloakAdminService keycloakAdmin;
    @Autowired private RoleService          roleService;

    public List<AppUserResponseDTO> findAll() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        roleService.assertCanManageUsers();
        return userRepository.findByTenantIdOrderByFullNameAsc(tenantId)
                .stream().filter(u -> u.getStatus() != AppUserStatus.DELETED)
                .map(AppUserResponseDTO::from).toList();
    }

    public List<AppUserResponseDTO> findByStore(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        roleService.assertCanManageUsers();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return userRepository.findByStoreIdAndTenantIdOrderByFullNameAsc(storeId, tenantId)
                .stream().filter(u -> u.getStatus() != AppUserStatus.DELETED)
                .map(AppUserResponseDTO::from).toList();
    }

    @Transactional
    public AppUserResponseDTO create(AppUserRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();

        // Cascada de creación (SPRINT-14): roleId == null también se rechaza
        // para no-root -- solo root puede crear una cuenta sin Role (acceso total).
        Role targetRole = dto.getRoleId() != null
                ? roleService.findOrThrow(dto.getRoleId(), tenantId)
                : null;
        roleService.assertCanManage(targetRole);

        if (userRepository.existsByUsernameAndTenantId(dto.getUsername().trim().toLowerCase(), tenantId)) {
            throw new IllegalArgumentException("El username '" + dto.getUsername() + "' ya esta en uso");
        }

        // storeId ya no es obligatorio (SPRINT-09): un perfil de solo lectura
        // (contador, socio) puede no tener local principal fijo.
        Store store = dto.getStoreId() != null
                ? TenantSecurityUtils.requireStore(dto.getStoreId(), tenantId, storeRepository)
                : null;

        Set<Store> accessibleStores = new HashSet<>();
        if (dto.getStoreIds() != null) {
            for (Long sid : dto.getStoreIds()) {
                accessibleStores.add(TenantSecurityUtils.requireStore(sid, tenantId, storeRepository));
            }
        }

        // Toda cuenta creada acá es "staff" en Keycloak -- la granularidad
        // real vive en el Role (tabla roles), no en Keycloak. Root se
        // provisiona fuera de este flujo (SPRINT-14).
        String keycloakId = keycloakAdmin.createUser(
                dto.getUsername(), dto.getFullName(), dto.getPassword(), STAFF_KEYCLOAK_ROLE, tenantId);

        AppUser user = new AppUser();
        user.setKeycloakId(keycloakId);
        user.setFullName(dto.getFullName().trim());
        user.setUsername(dto.getUsername().trim().toLowerCase());
        user.setStore(store);
        user.setStatus(AppUserStatus.ACTIVE);
        user.setTenantId(tenantId);
        user.setRole(targetRole);
        user.setAccessibleStores(accessibleStores);

        return AppUserResponseDTO.from(userRepository.save(user));
    }

    // ── Asignar Role (SPRINT-14) ─────────────────────────────────────────────

    @Transactional
    public AppUserResponseDTO updateRole(Long id, Long roleId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        AppUser user = findOrThrow(id);
        Role targetRole = roleId != null ? roleService.findOrThrow(roleId, tenantId) : null;
        roleService.assertCanManage(targetRole);
        user.setRole(targetRole);
        return AppUserResponseDTO.from(userRepository.save(user));
    }

    // ── Actualizar permisos de módulos ───────────────────────────────────────

    /** @deprecated SPRINT-14: los permisos ahora vienen del Role asignado
     * ({@link #updateRole}). Se conserva por compatibilidad con cuentas que
     * todavía no tengan Role (ver fallback en PermissionGuard). */
    @Deprecated
    @Transactional
    public AppUserResponseDTO updatePermissions(Long id, List<String> permissions) {
        AppUser user = findOrThrow(id);
        roleService.assertCanManage(user.getRole());
        user.setPermissions(permissions != null ? new HashSet<>(permissions) : new HashSet<>());
        return AppUserResponseDTO.from(userRepository.save(user));
    }

    // ── Actualizar locales accesibles ────────────────────────────────────────

    @Transactional
    public AppUserResponseDTO updateStoreAccess(Long id, List<Long> storeIds) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        AppUser user = findOrThrow(id);
        roleService.assertCanManage(user.getRole());
        Set<Store> stores = new HashSet<>();
        if (storeIds != null) {
            for (Long sid : storeIds) {
                // Valida que cada storeId pertenezca al tenant del caller antes de guardar
                // — PH v2 no hace esta validación porque es single-tenant.
                stores.add(TenantSecurityUtils.requireStore(sid, tenantId, storeRepository));
            }
        }
        user.setAccessibleStores(stores);
        return AppUserResponseDTO.from(userRepository.save(user));
    }

    @Transactional
    public AppUserResponseDTO suspend(Long id) {
        AppUser user = findOrThrow(id);
        roleService.assertCanManage(user.getRole());
        if (AppUserStatus.SUSPENDED == user.getStatus()) {
            throw new IllegalStateException("El usuario ya está suspendido");
        }
        keycloakAdmin.setUserEnabled(user.getKeycloakId(), false);
        user.setStatus(AppUserStatus.SUSPENDED);
        return AppUserResponseDTO.from(userRepository.save(user));
    }

    @Transactional
    public AppUserResponseDTO activate(Long id) {
        AppUser user = findOrThrow(id);
        roleService.assertCanManage(user.getRole());
        if (AppUserStatus.ACTIVE == user.getStatus()) {
            throw new IllegalStateException("El usuario ya está activo");
        }
        keycloakAdmin.setUserEnabled(user.getKeycloakId(), true);
        user.setStatus(AppUserStatus.ACTIVE);
        return AppUserResponseDTO.from(userRepository.save(user));
    }

    @Transactional
    public AppUserResponseDTO reassign(Long id, Long newStoreId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        AppUser user = findOrThrow(id);
        roleService.assertCanManage(user.getRole());
        Store store = TenantSecurityUtils.requireStore(newStoreId, tenantId, storeRepository);
        user.setStore(store);
        return AppUserResponseDTO.from(userRepository.save(user));
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser user = findOrThrow(id);
        roleService.assertCanManage(user.getRole());
        keycloakAdmin.resetPassword(user.getKeycloakId(), newPassword);
    }

    /** Soft-delete (SPRINT-14): nunca se borra la fila, se conserva para
     * trazabilidad. El usuario se deshabilita en Keycloak (no puede loguear)
     * pero su historial (ventas, movimientos, created_by) sigue intacto. */
    @Transactional
    public void delete(Long id) {
        AppUser user = findOrThrow(id);
        roleService.assertCanManage(user.getRole());
        keycloakAdmin.setUserEnabled(user.getKeycloakId(), false);
        user.setStatus(AppUserStatus.DELETED);
        userRepository.save(user);
    }

    public AppUserResponseDTO findByUsername(String username) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return userRepository.findByUsernameAndTenantId(username.toLowerCase(), tenantId)
                .map(AppUserResponseDTO::from)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + username));
    }

    private AppUser findOrThrow(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return userRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
    }
}
