package balance.tenant.controller;

import balance.tenant.dto.TenantRequestDTO;
import balance.tenant.dto.TenantResponseDTO;
import balance.tenant.service.TenantService;
import balance.tenant.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints de gestión de tenants.
 * TODO Sprint 2: proteger con @PreAuthorize("hasRole('ROOT')") cuando haya JWT.
 * Por ahora: solo accesible si tenantId = 0 (ROOT) vía header X-Tenant-ID.
 */
@RestController
@RequestMapping("/api/v3/tenants")
public class TenantController {

    private static final Long ROOT_TENANT_ID = 0L;

    @Autowired
    private TenantService tenantService;

    /** Solo ROOT (tenantId=0) puede listar todos los tenants. */
    @GetMapping
    public ResponseEntity<?> getAll() {
        if (!isRoot()) return forbidden();
        return ResponseEntity.ok(tenantService.findAll());
    }

    @GetMapping("/active")
    public ResponseEntity<?> getActive() {
        if (!isRoot()) return forbidden();
        return ResponseEntity.ok(tenantService.findAllActive());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Long tenantId = TenantContext.getTenantId();
        // ROOT puede ver cualquier tenant; un tenant puede ver solo el suyo
        if (!isRoot() && !id.equals(tenantId)) return forbidden();
        return tenantService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<?> getBySlug(@PathVariable String slug) {
        if (!isRoot()) return forbidden();
        return tenantService.findBySlug(slug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Solo ROOT puede crear tenants. */
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody TenantRequestDTO dto) {
        if (!isRoot()) return forbidden();
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(tenantService.create(dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody TenantRequestDTO dto) {
        if (!isRoot()) return forbidden();
        return tenantService.update(id, dto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable Long id) {
        if (!isRoot()) return forbidden();
        return tenantService.toggle(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!isRoot()) return forbidden();
        return tenantService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private boolean isRoot() {
        Long tenantId = TenantContext.getTenantId();
        return ROOT_TENANT_ID.equals(tenantId);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("Acceso denegado — se requiere rol ROOT");
    }
}
