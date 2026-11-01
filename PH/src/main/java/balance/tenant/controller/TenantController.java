package balance.tenant.controller;

import balance.tenant.context.PlatformSecurity;
import balance.tenant.context.TenantContext;
import balance.tenant.dto.TenantOnboardingRequestDTO;
import balance.tenant.dto.TenantRequestDTO;
import balance.tenant.service.TenantOnboardingService;
import balance.tenant.service.TenantService;
import balance.tenant.service.TenantStatusService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Gestión de empresas (tenants) — SPRINT-16.
 *
 * Solo el administrador de plataforma: tenant_id = 0 en el JWT Y rol de Keycloak "platform"
 * (ver {@link PlatformSecurity}). Una empresa solo puede consultar su propia fila (GET /{id}).
 *
 * La plataforma ve y administra las empresas y su configuración, NUNCA sus datos de negocio:
 * RLS (V12) le habilita únicamente las tablas tenants y tenant_config.
 */
@RestController
@RequestMapping("/api/v3/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final TenantOnboardingService onboardingService;
    private final TenantStatusService tenantStatusService;

    public TenantController(TenantService tenantService,
                            TenantOnboardingService onboardingService,
                            TenantStatusService tenantStatusService) {
        this.tenantService = tenantService;
        this.onboardingService = onboardingService;
        this.tenantStatusService = tenantStatusService;
    }

    @GetMapping
    public ResponseEntity<?> getAll() {
        if (!PlatformSecurity.isPlatformAdmin()) return forbidden();
        return ResponseEntity.ok(tenantService.findAll());
    }

    @GetMapping("/active")
    public ResponseEntity<?> getActive() {
        if (!PlatformSecurity.isPlatformAdmin()) return forbidden();
        return ResponseEntity.ok(tenantService.findAllActive());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        // La plataforma ve cualquier empresa; una empresa solo la suya.
        if (!PlatformSecurity.isPlatformAdmin() && !id.equals(TenantContext.getTenantId())) return forbidden();
        return tenantService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<?> getBySlug(@PathVariable String slug) {
        if (!PlatformSecurity.isPlatformAdmin()) return forbidden();
        return tenantService.findBySlug(slug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Alta completa: empresa + configuración + usuario dueño (root). */
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody TenantOnboardingRequestDTO dto) {
        if (!PlatformSecurity.isPlatformAdmin()) return forbidden();
        try {
            TenantOnboardingService.OnboardingResult result = onboardingService.onboard(dto);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "tenant", result.tenant(),
                    "rootUsername", result.rootUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody TenantRequestDTO dto) {
        if (!PlatformSecurity.isPlatformAdmin()) return forbidden();
        return tenantService.update(id, dto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Suspende o reactiva la empresa. Suspendida, sus usuarios reciben 403 TENANT_SUSPENDED. */
    @PutMapping("/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable Long id) {
        if (!PlatformSecurity.isPlatformAdmin()) return forbidden();
        if (id == TenantContext.PLATFORM_TENANT_ID) return ResponseEntity.badRequest().body(Map.of("error", "La plataforma no se puede suspender"));
        return tenantService.toggle(id)
                .map(dto -> {
                    tenantStatusService.evict(id);
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Borrar una empresa no está permitido: dejaría huérfanos todos sus datos de negocio, que la
     * plataforma ni siquiera puede ver (RLS). Para dar de baja, suspenderla con /toggle.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!PlatformSecurity.isPlatformAdmin()) return forbidden();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", "No se puede borrar una empresa. Suspéndela con PUT /api/v3/tenants/{id}/toggle"));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Acceso denegado: se requiere administrador de plataforma"));
    }
}
