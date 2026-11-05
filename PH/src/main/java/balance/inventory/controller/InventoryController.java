package balance.inventory.controller;

import balance.inventory.dto.*;
import balance.inventory.service.InventoryService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import balance.common.exception.ConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PermissionGuard permissionGuard;

    @GetMapping("/stores/{storeId}/stock")
    public ResponseEntity<List<StockItemDTO>> getStock(@PathVariable Long storeId) {
        // El POS (cajero) vende sobre el stock de su local; ajustes y movimientos siguen pidiendo INVENTORY.
        permissionGuard.assertAccessAny(storeId, PermissionModule.INVENTORY, PermissionModule.POS);
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

    // ENTRADA: cualquiera con INVENTORY en el local. SALIDA/AJUSTE: solo un encargado
    // (antes el comentario lo decía pero el código no lo validaba). El usuario del
    // movimiento sale del token, no del body.
    @PostMapping("/stores/{storeId}/stock/adjustment")
    public ResponseEntity<?> adjust(@PathVariable Long storeId,
                                     @Valid @RequestBody StockAdjustmentDTO dto) {
        if ("ENTRADA".equals(dto.getType())) {
            permissionGuard.assertAccess(PermissionModule.INVENTORY, storeId);
        } else {
            permissionGuard.assertManage(PermissionModule.INVENTORY, storeId);
        }
        String tokenUser = permissionGuard.currentUsername();
        if (tokenUser != null) {
            dto.setUsername(tokenUser);
        }
        try {
            return ResponseEntity.ok(inventoryService.adjust(storeId, dto));
        } catch (ConflictException e) {
            // Stock insuficiente para la salida (descuento atómico, ver InventoryService.adjust).
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
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

