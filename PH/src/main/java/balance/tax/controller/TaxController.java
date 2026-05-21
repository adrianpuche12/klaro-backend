package balance.tax.controller;

import balance.tax.dto.TaxDTO;
import balance.tax.service.TaxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v3/taxes")
public class TaxController {

    @Autowired
    private TaxService taxService;

    @GetMapping
    public List<TaxDTO> getAll() {
        return taxService.findAll();
    }

    @GetMapping("/active")
    public List<TaxDTO> getActive() {
        return taxService.findActive();
    }

    @PreAuthorize("hasRole('root')")
    @PostMapping
    public ResponseEntity<TaxDTO> create(@RequestBody TaxDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taxService.create(dto));
    }

    @PreAuthorize("hasRole('root')")
    @PutMapping("/{id}")
    public ResponseEntity<TaxDTO> update(@PathVariable Long id, @RequestBody TaxDTO dto) {
        return taxService.update(id, dto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('root')")
    @PutMapping("/{id}/toggle")
    public ResponseEntity<TaxDTO> toggle(@PathVariable Long id) {
        return taxService.toggle(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('root')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return taxService.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
