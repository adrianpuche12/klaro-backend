package balance.users.service;

import balance.common.enums.AppUserStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import balance.users.dto.AppUserRequestDTO;
import balance.users.dto.AppUserResponseDTO;
import balance.users.model.AppUser;
import balance.users.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import java.util.List;

@Service
public class AppUserService {

    @Autowired private AppUserRepository    userRepository;
    @Autowired private StoreRepository      storeRepository;
    @Autowired private KeycloakAdminService keycloakAdmin;

    public List<AppUserResponseDTO> findAll() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return userRepository.findByTenantIdOrderByFullNameAsc(tenantId)
                .stream().map(AppUserResponseDTO::from).toList();
    }

    public List<AppUserResponseDTO> findByStore(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return userRepository.findByStoreIdAndTenantIdOrderByFullNameAsc(storeId, tenantId)
                .stream().map(AppUserResponseDTO::from).toList();
    }

    @Transactional
    public AppUserResponseDTO create(AppUserRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();

        String role = (dto.getRole() != null) ? dto.getRole().toLowerCase() : "user";
        if (!Set.of("root", "admin", "user").contains(role)) {
            throw new IllegalArgumentException("Rol invalido: " + role);
        }

        // ADMIN solo puede crear usuarios con rol 'user'
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean callerIsRoot = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_root"));
        if (!callerIsRoot && !"user".equals(role)) {
            throw new IllegalArgumentException("Solo root puede crear usuarios con rol '" + role + "'");
        }

        if (userRepository.existsByUsernameAndTenantId(dto.getUsername().trim().toLowerCase(), tenantId)) {
            throw new IllegalArgumentException("El username '" + dto.getUsername() + "' ya esta en uso");
        }

        Store store = TenantSecurityUtils.requireStore(dto.getStoreId(), tenantId, storeRepository);

        // Crea en Keycloak con rol correcto y atributo tenant_id para que el JWT lo incluya
        String keycloakId = keycloakAdmin.createUser(
                dto.getUsername(), dto.getFullName(), dto.getPassword(), role, tenantId);

        AppUser user = new AppUser();
        user.setKeycloakId(keycloakId);
        user.setFullName(dto.getFullName().trim());
        user.setUsername(dto.getUsername().trim().toLowerCase());
        user.setStore(store);
        user.setStatus(AppUserStatus.ACTIVE);
        user.setTenantId(tenantId);

        return AppUserResponseDTO.from(userRepository.save(user));
    }

    @Transactional
    public AppUserResponseDTO suspend(Long id) {
        AppUser user = findOrThrow(id);
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
        Store store = TenantSecurityUtils.requireStore(newStoreId, tenantId, storeRepository);
        user.setStore(store);
        return AppUserResponseDTO.from(userRepository.save(user));
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser user = findOrThrow(id);
        keycloakAdmin.resetPassword(user.getKeycloakId(), newPassword);
    }

    @Transactional
    public void delete(Long id) {
        AppUser user = findOrThrow(id);
        keycloakAdmin.deleteUser(user.getKeycloakId());
        userRepository.delete(user);
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
