package balance.dashboard.controller;

import balance.dashboard.dto.DashboardDTO;
import balance.dashboard.service.DashboardService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/dashboard")
@CrossOrigin(origins = "*")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private PermissionGuard permissionGuard;

    // Resumen global del sistema para el admin: turnos activos, ventas del dia e inventario.
    // NOTA: gate a nivel de modulo solamente -- el agregado sigue siendo de TODOS los
    // locales del tenant. Filtrar el agregado por accessibleStores de un perfil acotado
    // requiere cambios en DashboardService, no cubierto en esta pasada (ver SPRINT-09).
    @GetMapping
    public ResponseEntity<DashboardDTO> getDashboard() {
        permissionGuard.assertAccess(PermissionModule.DASHBOARD);
        return ResponseEntity.ok(dashboardService.getDashboard());
    }
}

