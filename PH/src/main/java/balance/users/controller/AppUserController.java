package balance.users.controller;

import balance.users.dto.AppUserRequestDTO;
import balance.users.dto.AppUserResponseDTO;
import balance.users.service.AppUserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/users")
@CrossOrigin(origins = "*")
public class AppUserController {

    @Autowired
    private AppUserService userService;

    /** Lista todos los usuarios del tenant. Solo admin y root. */
    @PreAuthorize("hasAnyRole('root', 'admin')")
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

    /** Lista usuarios por local. Solo admin y root. */
    @PreAuthorize("hasAnyRole('root', 'admin')")
    @GetMapping("/store/{storeId}")
    public ResponseEntity<List<AppUserResponseDTO>> findByStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(userService.findByStore(storeId));
    }

    /** Crea un usuario. Admin puede crear solo USER; root puede crear ADMIN y USER. */
    @PreAuthorize("hasAnyRole('root', 'admin')")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody AppUserRequestDTO dto) {
        try {
            return ResponseEntity.ok(userService.create(dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Error al crear usuario: " + e.getMessage()));
        }
    }

    /** Suspende el acceso del usuario. Solo admin y root. */
    @PreAuthorize("hasAnyRole('root', 'admin')")
    @PutMapping("/{id}/suspend")
    public ResponseEntity<?> suspend(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(userService.suspend(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Reactiva el acceso del usuario. Solo admin y root. */
    @PreAuthorize("hasAnyRole('root', 'admin')")
    @PutMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(userService.activate(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Reasigna el usuario a otro local. Solo admin y root. */
    @PreAuthorize("hasAnyRole('root', 'admin')")
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

    /** Resetea la contraseña del usuario. Solo admin y root. */
    @PreAuthorize("hasAnyRole('root', 'admin')")
    @PutMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String newPassword = body.get("password");
            if (newPassword == null || newPassword.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "La nueva contrasena es obligatoria"));
            }
            userService.resetPassword(id, newPassword);
            return ResponseEntity.ok(Map.of("message", "Contrasena actualizada correctamente"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Elimina el usuario permanentemente. Solo root. */
    @PreAuthorize("hasRole('root')")
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
