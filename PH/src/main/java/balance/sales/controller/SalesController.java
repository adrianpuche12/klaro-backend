package balance.sales.controller;

import balance.common.exception.ConflictException;
import balance.sales.dto.*;
import balance.sales.service.IdempotentSaleResult;
import balance.sales.service.SalesService;
import balance.sales.service.ShiftAccessService;
import balance.sales.service.ShiftExpenseService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.ShiftReadScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class SalesController {

    @Autowired
    private SalesService salesService;

    @Autowired
    private PermissionGuard permissionGuard;

    @Autowired
    private ShiftExpenseService shiftExpenseService;

    // Permisos por turno/venta (POS para operar; alcance de lectura para consultar).
    // Siempre FUERA de los try/catch: AccessDeniedException debe terminar en 403.
    @Autowired
    private ShiftAccessService shiftAccess;

    // Registrar venta en un turno
    // Header opcional Idempotency-Key (UUID que genera el POS por venta): un reintento con la
    // misma clave devuelve la misma venta (mismo cuerpo, 200) en vez de cobrarla dos veces.
    // 409: stock insuficiente, o la clave ya se usó con otro pedido. 400: datos inválidos
    // (incluye un pago MIXED que no cuadra con el total).
    @PostMapping("/shifts/{shiftId}/sales")
    public ResponseEntity<?> createSale(@PathVariable Long shiftId,
                                         @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
                                         @Valid @RequestBody SaleRequestDTO request) {
        shiftAccess.assertCanOperate(shiftId);
        String tokenUser = permissionGuard.currentUsername();
        if (tokenUser != null) {
            request.setUsername(tokenUser); // quien vende es quien está logueado, no lo que diga el body
        }
        try {
            IdempotentSaleResult result = salesService.createSaleIdempotent(shiftId, request, idempotencyKey);
            if (result.replayed()) {
                // JSON exacto de la primera respuesta (String: se escribe tal cual).
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDEMPOTENT_REPLAYED_HEADER, "true")
                        .body(result.body());
            }
            return ResponseEntity.ok(result.body());
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    /**
     * Cotiza una venta sin registrarla: subtotal, impuestos, recargo y total calculados con el
     * MISMO código que POST /shifts/{shiftId}/sales, con el mismo cuerpo y los mismos permisos
     * (POS en el local del turno). El POS lo muestra antes de confirmar.
     */
    @PostMapping("/shifts/{shiftId}/sales/quote")
    public ResponseEntity<?> quoteSale(@PathVariable Long shiftId, @RequestBody SaleRequestDTO request) {
        shiftAccess.assertCanOperate(shiftId);
        try {
            return ResponseEntity.ok(salesService.quote(shiftId, request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Listar ventas de un turno
    @GetMapping("/shifts/{shiftId}/sales")
    public ResponseEntity<List<SaleResponseDTO>> getSalesByShift(@PathVariable Long shiftId) {
        shiftAccess.assertCanRead(shiftId);
        return ResponseEntity.ok(salesService.getSalesByShift(shiftId));
    }

    // Detalle de una venta
    @GetMapping("/sales/{saleId}")
    public ResponseEntity<?> getSale(@PathVariable Long saleId) {
        shiftAccess.assertCanReadSale(saleId);
        try {
            return ResponseEntity.ok(salesService.getSaleById(saleId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Cancelar venta (revierte stock)
    @DeleteMapping("/sales/{saleId}")
    public ResponseEntity<?> cancelSale(@PathVariable Long saleId) {
        shiftAccess.assertCanOperateSale(saleId);
        try {
            salesService.cancelSale(saleId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Resumen del turno (para vista de cierre)
    @GetMapping("/shifts/{shiftId}/summary")
    public ResponseEntity<?> getSummary(@PathVariable Long shiftId) {
        shiftAccess.assertCanRead(shiftId);
        try {
            return ResponseEntity.ok(salesService.getDailySummary(shiftId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Historial de ventas por local (admin) â€” opcional: ?from=yyyy-MM-dd&to=yyyy-MM-dd
    @GetMapping("/stores/{storeId}/sales")
    public ResponseEntity<List<SaleResponseDTO>> getSalesByStore(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String onlyUser = salesHistoryOnlyUser(storeId);
        List<SaleResponseDTO> sales = salesService.getSalesByStore(storeId, from, to);
        if (onlyUser != null) {
            sales = sales.stream()
                    .filter(s -> s.getUsername() != null && s.getUsername().equalsIgnoreCase(onlyUser))
                    .toList();
        }
        return ResponseEntity.ok(sales);
    }

    /**
     * P7: historial/resumen de ventas de un local. Exige SALES_HISTORY en ese local y respeta
     * el alcance de lectura de turnos: con OWN (cajero) devuelve el usuario del token, cuyas
     * ventas son las únicas que puede ver; con ALL, null (todas). Mismo criterio que
     * ReportsController. Fuera de los try/catch: AccessDeniedException tiene que dar 403.
     */
    private String salesHistoryOnlyUser(Long storeId) {
        permissionGuard.assertAccess(PermissionModule.SALES_HISTORY, storeId);
        return permissionGuard.shiftReadScope(storeId) == ShiftReadScope.OWN
                ? permissionGuard.currentUsername() : null;
    }

    // Resumen de ventas por local (admin) â€” opcional: ?from=yyyy-MM-dd&to=yyyy-MM-dd
    @GetMapping("/stores/{storeId}/sales/summary")
    public ResponseEntity<?> getSalesSummaryByStore(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String onlyUser = salesHistoryOnlyUser(storeId);
        try {
            return ResponseEntity.ok(salesService.getSummaryByStore(storeId, from, to, onlyUser));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Confirmar cierre de turno (integra con sistema V1)
    // "notes": observación opcional del cajero -- SPRINT-12
    // "openingCash"/"declaredCash": reconciliación de caja opcional -- SPRINT-12
    // (si se omite cualquiera de los dos, se cierra sin reconciliar, igual que antes)
    @PostMapping("/shifts/{shiftId}/closing")
    public ResponseEntity<?> closeShift(@PathVariable Long shiftId,
                                         @RequestBody Map<String, Object> body) {
        shiftAccess.assertCanOperate(shiftId);
        String tokenUser = permissionGuard.currentUsername();
        try {
            String username = tokenUser != null ? tokenUser : body.getOrDefault("username", "unknown").toString();
            String notes = body.get("notes") != null ? body.get("notes").toString() : null;
            BigDecimal openingCash = toBigDecimal(body.get("openingCash"));
            BigDecimal declaredCash = toBigDecimal(body.get("declaredCash"));
            return ResponseEntity.ok(salesService.closeShift(shiftId, username, notes, openingCash, declaredCash));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        return value != null ? new BigDecimal(value.toString()) : null;
    }

    // ── Egresos de caja del turno (SPRINT-12) ───────────────────────────────────

    @PostMapping("/shifts/{shiftId}/expenses")
    public ResponseEntity<?> addExpense(@PathVariable Long shiftId,
                                         @Valid @RequestBody ShiftExpenseRequestDTO request) {
        shiftAccess.assertCanOperate(shiftId);
        try {
            return ResponseEntity.ok(shiftExpenseService.addExpense(shiftId, request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/shifts/{shiftId}/expenses")
    public ResponseEntity<List<ShiftExpenseResponseDTO>> getExpenses(@PathVariable Long shiftId) {
        shiftAccess.assertCanRead(shiftId);
        return ResponseEntity.ok(shiftExpenseService.getExpensesForShift(shiftId));
    }
}

