package balance.catalog.controller;

import balance.catalog.dto.StoreRequestDTO;
import balance.catalog.dto.StoreResponseDTO;
import balance.catalog.service.StoreV2Service;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/stores")
@CrossOrigin(origins = "*")
public class StoreV2Controller {

    @Autowired
    private StoreV2Service storeV2Service;

    @GetMapping
    public ResponseEntity<List<StoreResponseDTO>> getAll() {
        return ResponseEntity.ok(storeV2Service.findAll());
    }

    @GetMapping("/active")
    public ResponseEntity<List<StoreResponseDTO>> getActive() {
        return ResponseEntity.ok(storeV2Service.findAllActive());
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoreResponseDTO> getById(@PathVariable Long id) {
        return storeV2Service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('root')")
    @PostMapping
    public ResponseEntity<StoreResponseDTO> create(@Valid @RequestBody StoreRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(storeV2Service.create(dto));
    }

    @PreAuthorize("hasRole('root')")
    @PutMapping("/{id}")
    public ResponseEntity<StoreResponseDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody StoreRequestDTO dto) {
        return storeV2Service.update(id, dto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('root')")
    @PutMapping("/{id}/toggle")
    public ResponseEntity<StoreResponseDTO> toggle(@PathVariable Long id) {
        return storeV2Service.toggle(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('root')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            if (storeV2Service.delete(id)) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }
}

