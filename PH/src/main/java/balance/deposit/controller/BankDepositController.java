package balance.deposit.controller;

import balance.deposit.dto.BankDepositRequestDTO;
import balance.deposit.dto.BankDepositResponseDTO;
import balance.deposit.dto.BankDepositUpdateDTO;
import balance.deposit.dto.PendingClosingDTO;
import balance.deposit.service.BankDepositService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * SPRINT-11 — depósitos bancarios (agrupar cierres de caja pendientes en un
 * depósito físico). Mismo módulo de permisos que el resto de "Operaciones"
 * (ver FormsController/OperationsV3Controller).
 */
@RestController
@RequestMapping("/api/v3/deposits")
public class BankDepositController {

    @Autowired private BankDepositService bankDepositService;
    @Autowired private PermissionGuard    permissionGuard;

    /** GET /api/v3/deposits/pending-closings?storeId=1&periodStart=...&periodEnd=... */
    @GetMapping("/pending-closings")
    public ResponseEntity<List<PendingClosingDTO>> pendingClosings(
            @RequestParam Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        permissionGuard.assertAccess(PermissionModule.OPERATIONS, storeId);
        return ResponseEntity.ok(bankDepositService.findPendingClosings(storeId, periodStart, periodEnd));
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody BankDepositRequestDTO dto) {
        permissionGuard.assertAccess(PermissionModule.OPERATIONS, dto.getStoreId());
        try {
            return ResponseEntity.ok(bankDepositService.create(dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/v3/deposits?storeId=1 (storeId opcional -- todos los locales del tenant si se omite) */
    @GetMapping
    public ResponseEntity<List<BankDepositResponseDTO>> findAll(@RequestParam(required = false) Long storeId) {
        permissionGuard.assertAccess(PermissionModule.OPERATIONS, storeId);
        return ResponseEntity.ok(bankDepositService.findAll(storeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        permissionGuard.assertAccess(PermissionModule.OPERATIONS);
        try {
            return ResponseEntity.ok(bankDepositService.findById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody BankDepositUpdateDTO dto) {
        permissionGuard.assertAccess(PermissionModule.OPERATIONS);
        try {
            return ResponseEntity.ok(bankDepositService.update(id, dto));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        permissionGuard.assertAccess(PermissionModule.OPERATIONS);
        try {
            bankDepositService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
