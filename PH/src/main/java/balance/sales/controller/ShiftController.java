package balance.sales.controller;

import balance.sales.dto.ShiftResponseDTO;
import balance.sales.service.ShiftAccessService;
import balance.sales.service.ShiftService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.ShiftReadScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Turnos del POS. Permisos (prioridad #1): operar exige el módulo POS en el local; leer
 * depende del alcance de {@link PermissionGuard#shiftReadScope} (un cajero solo ve sus turnos).
 * Los controles de permisos van FUERA de los try/catch: un AccessDeniedException tiene que
 * llegar a Spring Security (403), no convertirse en un 400.
 */
@RestController
@RequestMapping("/api/v2")
@CrossOrigin(origins = "*")
public class ShiftController {

    @Autowired
    private ShiftService shiftService;

    @Autowired
    private PermissionGuard permissionGuard;

    @Autowired
    private ShiftAccessService shiftAccess;

    // Abrir turno
    @PostMapping("/stores/{storeId}/shifts")
    public ResponseEntity<?> openShift(@PathVariable Long storeId,
                                        @RequestBody Map<String, String> body) {
        permissionGuard.assertAccess(PermissionModule.POS, storeId);
        String tokenUser = permissionGuard.currentUsername();
        try {
            String username = tokenUser != null ? tokenUser : body.getOrDefault("username", "unknown");
            return ResponseEntity.ok(shiftService.openShift(storeId, username));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "No se pudo abrir el turno: " + e.getMessage()));
        }
    }

    // Cerrar turno (solo cierra el turno, sin procesar ventas)
    @PutMapping("/shifts/{shiftId}/close")
    public ResponseEntity<?> closeShift(@PathVariable Long shiftId) {
        shiftAccess.assertCanOperate(shiftId);
        try {
            return ResponseEntity.ok(shiftService.closeShift(shiftId));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Turno activo de un local (cualquier alcance de lectura: el cajero lo necesita para vender)
    @GetMapping("/shifts/active/{storeId}")
    public ResponseEntity<?> getActiveShift(@PathVariable Long storeId) {
        permissionGuard.shiftReadScope(storeId);
        ShiftResponseDTO dto = shiftService.getActiveShift(storeId);
        if (dto == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(dto);
    }

    /**
     * Historial de turnos de un local (paginado — ?page=0&size=20).
     * Con alcance OWN (cajero) devuelve solo sus turnos, ignore lo que pida. Con alcance ALL,
     * {@code ?username=} filtra opcionalmente por quien abrió el turno.
     */
    @GetMapping("/stores/{storeId}/shifts")
    public ResponseEntity<List<ShiftResponseDTO>> getHistory(
            @PathVariable Long storeId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username) {
        ShiftReadScope scope = permissionGuard.shiftReadScope(storeId);
        if (scope == ShiftReadScope.OWN) {
            return ResponseEntity.ok(shiftService.getShiftHistoryByUser(
                    storeId, permissionGuard.currentUsername(), page, size));
        }
        if (username != null && !username.isBlank()) {
            return ResponseEntity.ok(shiftService.getShiftHistoryByUser(storeId, username.trim(), page, size));
        }
        return ResponseEntity.ok(shiftService.getShiftHistory(storeId, page, size));
    }

    // Detalle de un turno
    @GetMapping("/shifts/{shiftId}")
    public ResponseEntity<?> getById(@PathVariable Long shiftId) {
        shiftAccess.assertCanRead(shiftId);
        try {
            return ResponseEntity.ok(shiftService.getById(shiftId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
