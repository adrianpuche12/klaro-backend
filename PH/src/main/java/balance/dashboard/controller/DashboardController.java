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
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private PermissionGuard permissionGuard;

    // Resumen del día para el panel: turnos activos, ventas del día e inventario.
    // P7 (Etapa 1): el agregado se limita a los locales permitidos del usuario (root y las
    // cuentas sin perfil acotado ven todos). Muestra el turno ABIERTO de cada local, que
    // cualquier alcance de lectura de turnos ya puede ver (ver ShiftAccessService).
    @GetMapping
    public ResponseEntity<DashboardDTO> getDashboard() {
        permissionGuard.assertAccess(PermissionModule.DASHBOARD);
        return ResponseEntity.ok(dashboardService.getDashboard(permissionGuard.storeScope()));
    }
}

