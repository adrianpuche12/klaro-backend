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

    /**
     * Sin guard de módulo a propósito: todo usuario logueado (también el cajero y la
     * plataforma) necesita la zona horaria, la moneda y el recargo con tarjeta para mostrar
     * fechas y montos. Es seguro porque la empresa sale SIEMPRE del token
     * ({@code TenantSecurityUtils.requireTenantId}, nunca de un parámetro) y
     * {@link TenantConfigDTO} solo tiene datos de presentación (nombre, logo, dirección,
     * teléfono, moneda, zona, recargo): nada de credenciales ni datos de otros usuarios.
     * Si se agrega un campo sensible a la configuración, va en otro endpoint solo para root.
     */
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
