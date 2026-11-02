package balance.operations.controller;

import balance.tenant.service.TenantConfigService;
import balance.operations.dto.OperationDTO;
import balance.operations.dto.OperationSummaryDTO;
import balance.operations.service.OperationsV3Service;
import balance.operations.service.ReportService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.reports.ReportFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v3/operations")
public class OperationsV3Controller {

    @Autowired private OperationsV3Service operationsService;
    @Autowired private ReportService        reportService;
    @Autowired private TenantConfigService tenantConfigService;
    @Autowired private PermissionGuard      permissionGuard;

    /**
     * Lista unificada de operaciones con filtros y paginación.
     * GET /api/v3/operations?from=2026-06-01&to=2026-06-30&type=SALE,CLOSING&storeId=1&sort=DATE_DESC&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<List<OperationDTO>> getOperations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long storeId,
            @RequestParam(defaultValue = "DATE_DESC") String sort,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        permissionGuard.assertAccess(PermissionModule.OPERATIONS, storeId);

        // Defaults: último mes si no se especifica rango
        LocalDate effectiveTo   = to   != null ? to   : tenantConfigService.today();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(1);

        return ResponseEntity.ok(
                operationsService.getOperations(effectiveFrom, effectiveTo, type, storeId, sort, page, size));
    }

    /**
     * KPIs del período.
     * GET /api/v3/operations/summary?from=2026-06-01&to=2026-06-30
     */
    @GetMapping("/summary")
    public ResponseEntity<OperationSummaryDTO> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long storeId) {

        permissionGuard.assertAccess(PermissionModule.OPERATIONS, storeId);

        LocalDate effectiveTo   = to   != null ? to   : tenantConfigService.today();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(1);

        return ResponseEntity.ok(operationsService.getSummary(effectiveFrom, effectiveTo, storeId));
    }

    /**
     * Exportar reporte.
     * GET /api/v3/operations/export?format=PDF&from=...&to=...
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "PDF") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long storeId) {

        // Fuera del try/catch de abajo: si el guard deniega, tiene que propagar
        // como 403 real (via Spring Security), no quedar envuelto en el 500
        // genérico que ese catch usa para errores de generación de reporte.
        permissionGuard.assertAccess(PermissionModule.OPERATIONS, storeId);

        LocalDate effectiveTo   = to   != null ? to   : tenantConfigService.today();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(1);

        ReportFormat fmt = ReportFormat.parse(format);
        String filename = "operaciones-" + effectiveFrom + "-al-" + effectiveTo + "." + fmt.extension();
        try {
            byte[] data = reportService.exportOperations(effectiveFrom, effectiveTo, type, storeId, fmt);
            return buildResponse(data, filename, fmt.contentType());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("Error generando el informe: " + e.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private ResponseEntity<byte[]> buildResponse(byte[] data, String filename, String mediaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename).build());
        headers.setContentType(MediaType.parseMediaType(mediaType));
        headers.setContentLength(data.length);
        return ResponseEntity.ok().headers(headers).body(data);
    }
}
