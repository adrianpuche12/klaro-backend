package balance.users.controller;

import balance.users.dto.AppUserRequestDTO;
import balance.users.dto.AppUserResponseDTO;
import balance.users.service.AppUserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SPRINT-14: el gate de {@code @PreAuthorize} acá es deliberadamente amplio
 * (cualquier cuenta autenticada no-root es "staff" tras el colapso de roles
 * Keycloak) — la autorización fina (cascada de niveles, canManageUsers) vive
 * en {@code RoleService.assertCanManage}, llamada desde el service, porque
 * depende de datos (el Role asignado) que un SpEL de @PreAuthorize no puede
 * resolver limpiamente. Ver "06. Sistema de Roles y Permisos Personalizables".
 */
@RestController
@RequestMapping("/api/v2/users")
@CrossOrigin(origins = "*")
public class AppUserController {

    @Autowired
    private AppUserService userService;

    /** Lista todos los usuarios del tenant. Root, o cualquier Role con canManageUsers=true. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @GetMapping
    public ResponseEntity<List<AppUserResponseDTO>> findAll() {
        return ResponseEntity.ok(userService.findAll());
    }

    /** Perfil de un usuario por username. Accesible para todos los roles autenticados. */
    @GetMapping("/by-username/{username}")
    public ResponseEntity<?> findByUsername(@PathVariable String username) {
        try {
            return ResponseEntity.ok(userService.findByUsername(username));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Lista usuarios por local. Root, o cualquier Role con canManageUsers=true. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @GetMapping("/store/{storeId}")
    public ResponseEntity<List<AppUserResponseDTO>> findByStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(userService.findByStore(storeId));
    }

    /** Crea un usuario. La cascada de niveles se valida en el service. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody AppUserRequestDTO dto) {
        try {
            return ResponseEntity.ok(userService.create(dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (AccessDeniedException e) {
            // Dejar que Spring Security la traduzca a 403 -- atraparla acá como
            // Exception genérica la enmascaraba como 500 (bug encontrado en
            // testing E2E: violar la cascada de niveles daba 500, no 403).
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Error al crear usuario: " + e.getMessage()));
        }
    }

    /** Suspende el acceso del usuario. Cascada de niveles validada en el service. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @PutMapping("/{id}/suspend")
    public ResponseEntity<?> suspend(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(userService.suspend(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Reactiva el acceso del usuario. Cascada de niveles validada en el service. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @PutMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(userService.activate(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Reasigna el usuario a otro local. Cascada de niveles validada en el service. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @PutMapping("/{id}/reassign")
    public ResponseEntity<?> reassign(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        try {
            Long newStoreId = body.get("storeId");
            if (newStoreId == null) return ResponseEntity.badRequest().body(Map.of("error", "storeId es obligatorio"));
            return ResponseEntity.ok(userService.reassign(id, newStoreId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Asigna un Role al usuario (SPRINT-14). Reemplaza a /permissions como vía principal. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @PutMapping("/{id}/role")
    public ResponseEntity<?> updateRole(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        try {
            return ResponseEntity.ok(userService.updateRole(id, body.get("roleId")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** @deprecated SPRINT-14: usar /role. Se conserva por compatibilidad. */
    @Deprecated
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @PutMapping("/{id}/permissions")
    public ResponseEntity<?> updatePermissions(@PathVariable Long id, @RequestBody Map<String, List<String>> body) {
        try {
            return ResponseEntity.ok(userService.updatePermissions(id, body.get("permissions")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Actualiza los locales accesibles del usuario. Cascada de niveles validada en el service. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @PutMapping("/{id}/store-access")
    public ResponseEntity<?> updateStoreAccess(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        try {
            return ResponseEntity.ok(userService.updateStoreAccess(id, body.get("storeIds")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Resetea la contraseña del usuario. Cascada de niveles validada en el service. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @PutMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String newPassword = body.get("password");
            if (newPassword == null || newPassword.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "La nueva contraseña es obligatoria"));
            }
            userService.resetPassword(id, newPassword);
            return ResponseEntity.ok(Map.of("message", "Contraseña actualizada correctamente"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Elimina (soft-delete) el usuario. Cascada de niveles validada en el service. */
    @PreAuthorize("hasAnyRole('root', 'staff')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            userService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
