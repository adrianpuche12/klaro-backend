package balance.users.service;

import balance.model.Store;
import balance.tenant.context.TenantSecurityUtils;
import balance.users.dto.RoleRequestDTO;
import balance.users.dto.RoleResponseDTO;
import balance.users.model.AppUser;
import balance.users.model.Role;
import balance.users.repository.AppUserRepository;
import balance.users.repository.RoleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Roles configurables definidos por root (SPRINT-14). Root los crea/edita/
 * elimina — sin excepciones, no es un PermissionModule otorgable (ver
 * "06. Sistema de Roles y Permisos Personalizables - Diseno", sección 6).
 */
@Service
public class RoleService {

    @Autowired private RoleRepository roleRepository;
    @Autowired private AppUserRepository userRepository;

    /**
     * Root ve todos los Roles del tenant. Un usuario con canManageUsers=true
     * ve solo los Roles de nivel mayor al suyo (los únicos que puede asignar
     * al crear/editar un usuario) — mismo criterio que {@link #assertCanManage}.
     */
    public List<RoleResponseDTO> findAll() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<Role> roles = roleRepository.findByTenantIdOrderByLevelAscNameAsc(tenantId);

        if (hasRealmRole(auth, "root")) {
            return roles.stream().map(RoleResponseDTO::from).toList();
        }

        String keycloakId = currentKeycloakId(auth);
        Role callerRole = userRepository.findByKeycloakIdAndTenantId(keycloakId, tenantId)
                .map(AppUser::getRole)
                .orElse(null);
        int callerLevel = callerRole != null ? callerRole.getLevel() : Integer.MAX_VALUE;

        return roles.stream()
                .filter(r -> r.getLevel() > callerLevel)
                .map(RoleResponseDTO::from)
                .toList();
    }

    @Transactional
    public RoleResponseDTO create(RoleRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        if (roleRepository.existsByTenantIdAndName(tenantId, dto.getName().trim())) {
            throw new IllegalArgumentException("Ya existe un Role llamado '" + dto.getName() + "'");
        }

        Role role = new Role();
        role.setTenantId(tenantId);
        role.setName(dto.getName().trim());
        role.setLevel(dto.getLevel());
        role.setCanManageUsers(dto.isCanManageUsers());
        role.setPermissions(dto.getPermissions() != null ? new HashSet<>(dto.getPermissions()) : new HashSet<>());
        role.setCreatedBy(currentAppUserId(tenantId));

        return RoleResponseDTO.from(roleRepository.save(role));
    }

    @Transactional
    public RoleResponseDTO update(Long id, RoleRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Role role = findOrThrow(id, tenantId);

        if (!role.getName().equals(dto.getName().trim())
                && roleRepository.existsByTenantIdAndName(tenantId, dto.getName().trim())) {
            throw new IllegalArgumentException("Ya existe un Role llamado '" + dto.getName() + "'");
        }

        role.setName(dto.getName().trim());
        role.setLevel(dto.getLevel());
        role.setCanManageUsers(dto.isCanManageUsers());
        role.setPermissions(dto.getPermissions() != null ? new HashSet<>(dto.getPermissions()) : new HashSet<>());

        return RoleResponseDTO.from(roleRepository.save(role));
    }

    @Transactional
    public void delete(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Role role = findOrThrow(id, tenantId);
        if (userRepository.existsByRoleId(role.getId())) {
            throw new IllegalStateException(
                    "No se puede eliminar: hay usuarios con este Role asignado. Reasignalos primero.");
        }
        roleRepository.delete(role);
    }

    /**
     * Cascada de creación/gestión (Regla 1 del diseño): un usuario solo puede
     * crear/suspender/eliminar usuarios cuyo Role tenga nivel estrictamente
     * mayor al del propio Role del caller. Root queda exento — gestiona
     * cualquier Role, siempre, sin excepciones (nivel implícito 0).
     *
     * targetRole == null (usuario legacy, sin perfil acotado / acceso total)
     * también se rechaza para no-root: solo root puede dejar a alguien sin Role.
     */
    public void assertCanManage(Role targetRole) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (hasRealmRole(auth, "root")) {
            return;
        }

        String keycloakId = currentKeycloakId(auth);
        AppUser caller = userRepository.findByKeycloakIdAndTenantId(keycloakId, tenantId)
                .orElseThrow(() -> new AccessDeniedException("Acceso denegado"));
        Role callerRole = caller.getRole();

        if (callerRole == null || !callerRole.isCanManageUsers()) {
            throw new AccessDeniedException("No tenés permiso para gestionar usuarios");
        }
        if (targetRole == null || targetRole.getLevel() <= callerRole.getLevel()) {
            throw new AccessDeniedException(
                    "No podés crear o gestionar un usuario de tu mismo nivel o superior");
        }
    }

    /**
     * P5: un encargado no puede darle a otro usuario locales que él mismo no tiene
     * (al crear, al cambiar sus locales o su local principal). Root no tiene límite.
     * Se llama además de {@link #assertCanManage}: esa valida el nivel, esta el alcance.
     */
    public void assertCanGrantStores(Collection<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return;
        }
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (hasRealmRole(auth, "root")) {
            return;
        }
        AppUser caller = requireCaller(auth, tenantId);
        Set<Long> own = caller.getAccessibleStores().stream().map(Store::getId).collect(Collectors.toSet());
        for (Long id : storeIds) {
            if (id != null && !own.contains(id)) {
                throw new AccessDeniedException("No podés asignar un local al que vos no tenés acceso");
            }
        }
    }

    /**
     * P5: un encargado no puede darle a otro usuario módulos que él mismo no tiene
     * (al asignarle un Role, o con el endpoint viejo de permisos sueltos). Root no tiene límite.
     */
    public void assertCanGrantModules(Collection<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return;
        }
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (hasRealmRole(auth, "root")) {
            return;
        }
        AppUser caller = requireCaller(auth, tenantId);
        Set<String> own = caller.getRole() != null ? caller.getRole().getPermissions() : Set.of();
        for (String module : modules) {
            if (!own.contains(module)) {
                throw new AccessDeniedException("No podés asignar un módulo que vos no tenés: " + module);
            }
        }
    }

    private AppUser requireCaller(Authentication auth, Long tenantId) {
        return userRepository.findByKeycloakIdAndTenantId(currentKeycloakId(auth), tenantId)
                .orElseThrow(() -> new AccessDeniedException("Acceso denegado"));
    }

    /** Gate para endpoints de lectura de usuarios (listar todos / por local):
     * root o cualquier Role con canManageUsers=true. No compara niveles porque
     * no hay un usuario objetivo puntual todavía (eso lo hace assertCanManage
     * en las mutaciones). */
    public void assertCanManageUsers() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (hasRealmRole(auth, "root")) {
            return;
        }
        String keycloakId = currentKeycloakId(auth);
        Role callerRole = userRepository.findByKeycloakIdAndTenantId(keycloakId, tenantId)
                .map(AppUser::getRole)
                .orElse(null);
        if (callerRole == null || !callerRole.isCanManageUsers()) {
            throw new AccessDeniedException("No tenés permiso para ver esta información");
        }
    }

    public Role findOrThrow(Long id, Long tenantId) {
        return roleRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Role no encontrado"));
    }

    private Long currentAppUserId(Long tenantId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String keycloakId = currentKeycloakId(auth);
        return userRepository.findByKeycloakIdAndTenantId(keycloakId, tenantId)
                .map(AppUser::getId)
                .orElse(null); // root no tiene fila propia necesariamente resoluble acá; se permite null
    }

    private boolean hasRealmRole(Authentication auth, String role) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    private String currentKeycloakId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        throw new AccessDeniedException("No autenticado");
    }
}
