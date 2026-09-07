package balance.users.controller;

import balance.users.dto.RoleRequestDTO;
import balance.users.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/roles")
@CrossOrigin(origins = "*")
public class RoleController {

    @Autowired
    private RoleService roleService;

    /** Root ve todos los Roles del tenant; un usuario con canManageUsers=true
     * ve solo los que puede asignar (nivel mayor al suyo). */
    @GetMapping
    public ResponseEntity<?> findAll() {
        return ResponseEntity.ok(roleService.findAll());
    }

    /** Crear un Role nuevo. Solo root — no es algo que un Role pueda delegar a otro. */
    @PreAuthorize("hasRole('root')")
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody RoleRequestDTO dto) {
        try {
            return ResponseEntity.ok(roleService.create(dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('root')")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody RoleRequestDTO dto) {
        try {
            return ResponseEntity.ok(roleService.update(id, dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('root')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            roleService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }
}
