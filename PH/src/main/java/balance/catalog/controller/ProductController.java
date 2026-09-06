package balance.catalog.controller;

import balance.catalog.dto.ProductRequestDTO;
import balance.catalog.dto.ProductResponseDTO;
import balance.catalog.service.ProductService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class ProductController {

    @Autowired
    private ProductService productService;

    @Autowired
    private PermissionGuard permissionGuard;

    @GetMapping("/api/v2/stores/{storeId}/products")
    public ResponseEntity<List<ProductResponseDTO>> getByStore(
            @PathVariable Long storeId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search) {
        permissionGuard.assertAccess(PermissionModule.CATALOG, storeId);
        return ResponseEntity.ok(productService.findByStore(storeId, active, categoryId, search));
    }

    @GetMapping("/api/v2/products/{id}")
    public ResponseEntity<ProductResponseDTO> getById(@PathVariable Long id) {
        return productService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v2/stores/{storeId}/products")
    public ResponseEntity<?> create(@PathVariable Long storeId,
                                    @Valid @RequestBody ProductRequestDTO dto) {
        try {
            return productService.create(storeId, dto)
                    .map(p -> ResponseEntity.status(HttpStatus.CREATED).body(p))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/api/v2/products/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                     @Valid @RequestBody ProductRequestDTO dto) {
        try {
            return productService.update(id, dto)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/api/v2/products/{id}/toggle")
    public ResponseEntity<ProductResponseDTO> toggle(@PathVariable Long id) {
        return productService.toggle(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/v2/products/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (productService.delete(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}

