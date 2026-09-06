package balance.catalog.controller;

import balance.catalog.dto.CategoryRequestDTO;
import balance.catalog.dto.CategoryResponseDTO;
import balance.catalog.service.CategoryService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin(origins = "*")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private PermissionGuard permissionGuard;

    //Ãrbol completo de categorÃ­as de un local
    @GetMapping("/api/v2/stores/{storeId}/categories")
    public ResponseEntity<List<CategoryResponseDTO>> getTree(@PathVariable Long storeId) {
        permissionGuard.assertAccess(PermissionModule.CATALOG, storeId);
        return ResponseEntity.ok(categoryService.getTree(storeId));
    }

    // Detalle de una categorÃ­a
    @GetMapping("/api/v2/categories/{id}")
    public ResponseEntity<CategoryResponseDTO> getById(@PathVariable Long id) {
        return categoryService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Crear categorÃ­a raÃ­z
    @PostMapping("/api/v2/stores/{storeId}/categories")
    public ResponseEntity<CategoryResponseDTO> createRoot(@PathVariable Long storeId,
                                                           @Valid @RequestBody CategoryRequestDTO dto) {
        return categoryService.createRoot(storeId, dto)
                .map(cat -> ResponseEntity.status(HttpStatus.CREATED).body(cat))
                .orElse(ResponseEntity.notFound().build());
    }

    // Crear subcategorÃ­a (sin lÃ­mite de profundidad)
    @PostMapping("/api/v2/stores/{storeId}/categories/{parentId}/children")
    public ResponseEntity<CategoryResponseDTO> createChild(@PathVariable Long storeId,
                                                            @PathVariable Long parentId,
                                                            @Valid @RequestBody CategoryRequestDTO dto) {
        return categoryService.createChild(storeId, parentId, dto)
                .map(cat -> ResponseEntity.status(HttpStatus.CREATED).body(cat))
                .orElse(ResponseEntity.notFound().build());
    }

    // Editar categorÃ­a
    @PutMapping("/api/v2/categories/{id}")
    public ResponseEntity<CategoryResponseDTO> update(@PathVariable Long id,
                                                       @Valid @RequestBody CategoryRequestDTO dto) {
        return categoryService.update(id, dto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Activar/desactivar categorÃ­a
    @PutMapping("/api/v2/categories/{id}/toggle")
    public ResponseEntity<CategoryResponseDTO> toggle(@PathVariable Long id) {
        return categoryService.toggle(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Eliminar categorÃ­a
    @DeleteMapping("/api/v2/categories/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (categoryService.delete(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}

