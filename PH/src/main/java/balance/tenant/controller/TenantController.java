package balance.tenant.controller;

import balance.tenant.dto.TenantRequestDTO;
import balance.tenant.dto.TenantResponseDTO;
import balance.tenant.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v3/tenants")
public class TenantController {

    @Autowired
    private TenantService tenantService;

    @GetMapping
    public List<TenantResponseDTO> getAll() {
        return tenantService.findAll();
    }

    @GetMapping("/active")
    public List<TenantResponseDTO> getActive() {
        return tenantService.findAllActive();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantResponseDTO> getById(@PathVariable Long id) {
        return tenantService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<TenantResponseDTO> getBySlug(@PathVariable String slug) {
        return tenantService.findBySlug(slug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody TenantRequestDTO dto) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(tenantService.create(dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<TenantResponseDTO> update(@PathVariable Long id,
                                                     @Valid @RequestBody TenantRequestDTO dto) {
        return tenantService.update(id, dto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/toggle")
    public ResponseEntity<TenantResponseDTO> toggle(@PathVariable Long id) {
        return tenantService.toggle(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return tenantService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
