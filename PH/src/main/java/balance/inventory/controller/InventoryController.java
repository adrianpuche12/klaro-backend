package balance.inventory.controller;

import balance.inventory.dto.*;
import balance.inventory.service.InventoryService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
@CrossOrigin(origins = "*")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PermissionGuard permissionGuard;

    @GetMapping("/stores/{storeId}/stock")
    public ResponseEntity<List<StockItemDTO>> getStock(@PathVariable Long storeId) {
        permissionGuard.assertAccess(PermissionModule.INVENTORY, storeId);
        return ResponseEntity.ok(inventoryService.getStock(storeId));
    }

    @GetMapping("/stores/{storeId}/stock/low")
    public ResponseEntity<List<StockItemDTO>> getLowStock(@PathVariable Long storeId) {
        permissionGuard.assertAccess(PermissionModule.INVENTORY, storeId);
        return ResponseEntity.ok(inventoryService.getLowStock(storeId));
    }

    @GetMapping("/stores/{storeId}/stock/summary")
    public ResponseEntity<StockSummaryDTO> getSummary(@PathVariable Long storeId) {
        permissionGuard.assertAccess(PermissionModule.INVENTORY, storeId);
        return ResponseEntity.ok(inventoryService.getSummary(storeId));
    }

    // Solo admin puede crear ajustes manuales (SALIDA/AJUSTE); user solo ENTRADA
    @PostMapping("/stores/{storeId}/stock/adjustment")
    public ResponseEntity<?> adjust(@PathVariable Long storeId,
                                     @Valid @RequestBody StockAdjustmentDTO dto) {
        try {
            permissionGuard.assertAccess(PermissionModule.INVENTORY, storeId);
            return ResponseEntity.ok(inventoryService.adjust(storeId, dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stores/{storeId}/stock/movements")
    public ResponseEntity<List<MovementDTO>> getMovements(@PathVariable Long storeId) {
        permissionGuard.assertAccess(PermissionModule.INVENTORY, storeId);
        return ResponseEntity.ok(inventoryService.getMovements(storeId));
    }
}

