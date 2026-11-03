package balance.reports;

import balance.tenant.context.RecursoNoEncontradoException;
import balance.tenant.service.TenantConfigService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.ShiftReadScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Informes descargables (PDF, Excel, CSV).
 *
 *   GET /api/v3/reports/sales?storeId=&from=&to=&format=PDF|EXCEL|CSV
 *   GET /api/v3/reports/products-sold?storeId=&from=&to=&format=…
 *
 * Permisos: módulo SALES_HISTORY en el local. Sin storeId ("todos los locales") solo para
 * quien no tiene locales acotados (root / acceso total). Si el usuario solo puede ver SUS
 * turnos (cajero), el informe trae únicamente sus ventas. Los controles van fuera del
 * try/catch para que un rechazo sea 403, no 500.
 */
@RestController
@RequestMapping("/api/v3/reports")
public class ReportsController {

    @Autowired private SalesReportService salesReports;
    @Autowired private PermissionGuard permissionGuard;
    @Autowired private TenantConfigService tenantConfigService;

    @GetMapping("/sales")
    public ResponseEntity<byte[]> sales(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "PDF") String format) {
        String onlyUser = authorizeSales(storeId);
        LocalDate[] range = range(from, to);
        ReportFormat fmt = ReportFormat.parse(format);
        try {
            byte[] data = salesReports.salesDetail(storeId, range[0], range[1], onlyUser, fmt);
            return file(data, "ventas-" + range[0] + "-al-" + range[1] + "." + fmt.extension(), fmt);
        } catch (RecursoNoEncontradoException e) {
            throw e; // 404: el local no es de esta empresa (no un error del servidor)
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/products-sold")
    public ResponseEntity<byte[]> productsSold(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "PDF") String format) {
        String onlyUser = authorizeSales(storeId);
        LocalDate[] range = range(from, to);
        ReportFormat fmt = ReportFormat.parse(format);
        try {
            byte[] data = salesReports.productsSold(storeId, range[0], range[1], onlyUser, fmt);
            return file(data, "productos-vendidos-" + range[0] + "-al-" + range[1] + "." + fmt.extension(), fmt);
        } catch (RecursoNoEncontradoException e) {
            throw e; // 404: el local no es de esta empresa (no un error del servidor)
        } catch (Exception e) {
            return error(e);
        }
    }

    /** Valida el acceso y devuelve el usuario al que hay que limitar el informe (o null = todos). */
    private String authorizeSales(Long storeId) {
        permissionGuard.assertAccess(PermissionModule.SALES_HISTORY, storeId);
        if (storeId == null) {
            return null; // assertAccess con storeId null solo deja pasar a quien no tiene locales acotados
        }
        return permissionGuard.shiftReadScope(storeId) == ShiftReadScope.OWN
                ? permissionGuard.currentUsername() : null;
    }

    /** Rango por defecto: el mes en curso hasta hoy (fecha de la empresa). */
    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : tenantConfigService.today();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("La fecha 'desde' es posterior a 'hasta'");
        }
        return new LocalDate[]{start, end};
    }

    private ResponseEntity<byte[]> file(byte[] data, String filename, ReportFormat fmt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setContentType(MediaType.parseMediaType(fmt.contentType()));
        headers.setContentLength(data.length);
        headers.setAccessControlExposeHeaders(java.util.List.of(HttpHeaders.CONTENT_DISPOSITION));
        return ResponseEntity.ok().headers(headers).body(data);
    }

    private ResponseEntity<byte[]> error(Exception e) {
        return ResponseEntity.internalServerError()
                .contentType(MediaType.TEXT_PLAIN)
                .body(("No se pudo generar el informe: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /** Local que no es de esta empresa: 404, igual que en el resto de la API. */
    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<String> notFound(RecursoNoEncontradoException e) {
        return ResponseEntity.status(404).body(e.getMessage());
    }
}
