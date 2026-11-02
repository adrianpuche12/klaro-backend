package balance.operations.service;

import balance.operations.dto.OperationDTO;
import balance.reports.ReportContext;
import balance.reports.ReportContextFactory;
import balance.reports.ReportFormat;
import balance.reports.ReportRenderer;
import balance.reports.ReportTable;
import balance.reports.ReportTable.Column;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Informe de operaciones (cierres, ventas, pagos, sueldos, gastos, transacciones, depósitos)
 * sobre el motor de informes (balance.reports): PDF, Excel o CSV con los datos de la empresa.
 *
 * Totales POR TIPO, no un "monto total" único: antes se sumaban ingresos, egresos y depósitos
 * en una sola cifra que no significaba nada.
 */
@Service
public class ReportService {

    /** Nombre visible de cada tipo de operación. */
    static final Map<String, String> TYPE_LABEL = Map.of(
            "CLOSING", "Cierre de caja",
            "SALE", "Venta",
            "SUPPLIER", "Pago a proveedor",
            "SALARY", "Sueldo",
            "GASTO_ADMIN", "Gasto administrativo",
            "TRANSACTION", "Transacción",
            "BANK_DEPOSIT", "Depósito bancario");

    @Autowired private OperationsV3Service operationsService;
    @Autowired private ReportContextFactory contextFactory;
    @Autowired private ReportRenderer renderer;

    public byte[] exportOperations(LocalDate from, LocalDate to, String typeFilter, Long storeId,
                                   ReportFormat format) throws IOException {
        List<OperationDTO> operations = operationsService.getAllForExport(from, to, typeFilter, storeId);
        ReportContext ctx = contextFactory.current();
        return renderer.render(buildOperationsTable(operations, from, to, ctx), ctx, format);
    }

    ReportTable buildOperationsTable(List<OperationDTO> operations, LocalDate from, LocalDate to, ReportContext ctx) {
        ReportTable t = new ReportTable(
                "Informe de operaciones",
                "Período: " + ctx.date(from) + " al " + ctx.date(to),
                List.of(
                        Column.date("Fecha", 11),
                        Column.text("Tipo", 15),
                        Column.text("Local", 14),
                        Column.text("Usuario", 13),
                        Column.text("Descripción", 30),
                        Column.text("Medio de pago", 11),
                        Column.money("Monto", 13)));

        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (OperationDTO op : operations) {
            String label = TYPE_LABEL.getOrDefault(op.getType(), op.getType());
            BigDecimal amount = op.getAmount() != null ? op.getAmount() : BigDecimal.ZERO;
            t.row(op.getDate(), label, op.getStoreName(), op.getUsername(), op.getDescription(),
                    paymentLabel(op.getPaymentMethod()), amount);
            byType.merge(label, amount, BigDecimal::add);
        }
        t.total("Cantidad de operaciones", operations.size(), ReportTable.ColType.NUMBER);
        byType.forEach((label, sum) -> t.total("Total " + label.toLowerCase(), sum, ReportTable.ColType.MONEY));
        return t;
    }

    static String paymentLabel(String method) {
        if (method == null) return "";
        return switch (method) {
            case "CASH" -> "Efectivo";
            case "CARD" -> "Tarjeta";
            case "MIXED" -> "Mixto";
            default -> method;
        };
    }
}
