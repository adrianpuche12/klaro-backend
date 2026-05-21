package balance.tenant.controller;

import balance.tenant.dto.TenantConfigDTO;
import balance.tenant.service.TenantConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v3/tenant/config")
public class TenantConfigController {

    @Autowired
    private TenantConfigService tenantConfigService;

    @GetMapping
    public ResponseEntity<TenantConfigDTO> getConfig() {
        return ResponseEntity.ok(tenantConfigService.getConfig());
    }

    @PreAuthorize("hasRole('root')")
    @PutMapping
    public ResponseEntity<TenantConfigDTO> updateConfig(@RequestBody TenantConfigDTO dto) {
        return ResponseEntity.ok(tenantConfigService.updateConfig(dto));
    }
}
