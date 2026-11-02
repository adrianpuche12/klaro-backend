package balance.operations.service;

import balance.operations.dto.OperationDTO;
import balance.reports.ReportContext;
import balance.reports.ReportContextFactory;
import balance.reports.ReportFormat;
import balance.reports.ReportRenderer;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** Informe de operaciones sobre el motor de informes: contenido real de PDF, Excel y CSV. */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 5, 31);

    @InjectMocks private ReportService reportService;
    @Mock private OperationsV3Service operationsService;
    @Mock private ReportContextFactory contextFactory;
    @Spy  private ReportRenderer renderer = new ReportRenderer();

    private ReportContext ctx(String currency) {
        return new ReportContext("Ferretería Los Andes", "Av. Siempre Viva 123", "+56 2 2345 6789",
                currency, ZoneId.of("America/Santiago"), LocalDateTime.of(2026, 6, 1, 10, 30));
    }

    private OperationDTO op(String type, String amount, String store) {
        return OperationDTO.of(type, 1L, LocalDate.of(2026, 5, 21), new BigDecimal(amount),
                1L, store, "cajero01", "Detalle de prueba", "CONFIRMED", "CASH", null);
    }

    private byte[] export(List<OperationDTO> ops, String currency, ReportFormat fmt) throws Exception {
        when(operationsService.getAllForExport(any(), any(), isNull(), isNull())).thenReturn(ops);
        when(contextFactory.current()).thenReturn(ctx(currency));
        return reportService.exportOperations(FROM, TO, null, null, fmt);
    }

    @Test
    void pdf_isAValidPdf_withCompanyName() throws Exception {
        byte[] pdf = export(List.of(op("SALE", "1500.50", "Centro")), "ARS", ReportFormat.PDF);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(800);
    }

    @Test
    void pdf_withoutData_stillGenerates() throws Exception {
        byte[] pdf = export(List.of(), "ARS", ReportFormat.PDF);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void excel_amountsAreNumbers_andDatesAreRealDates() throws Exception {
        byte[] xlsx = export(List.of(op("SALE", "1500.50", "Centro"), op("SALARY", "300", "Centro")),
                "ARS", ReportFormat.EXCEL);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Ferretería Los Andes");
            Row header = findRow(s, "Fecha");
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("Monto (ARS)");
            Row first = s.getRow(header.getRowNum() + 1);
            assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);   // fecha real
            assertThat(first.getCell(6).getCellType()).isEqualTo(CellType.NUMERIC);   // monto numérico
            assertThat(first.getCell(6).getNumericCellValue()).isEqualTo(1500.50);
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("Venta");
        }
    }

    @Test
    void excel_currencyWithoutCents_usesIntegerFormat() throws Exception {
        byte[] xlsx = export(List.of(op("SALE", "15990", "Centro")), "CLP", ReportFormat.EXCEL);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet s = wb.getSheetAt(0);
            Row first = s.getRow(findRow(s, "Fecha").getRowNum() + 1);
            assertThat(first.getCell(6).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
        }
    }

    @Test
    void csv_hasBom_semicolon_commaDecimal_andTotalsByType() throws Exception {
        byte[] csv = export(List.of(op("SALE", "1500.50", "Centro"), op("SALE", "99.50", "Centro"),
                op("SUPPLIER", "200", "Centro")), "ARS", ReportFormat.CSV);
        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
        String text = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertThat(text).startsWith("Fecha;Tipo;Local;Usuario;Descripción;Medio de pago;Monto (ARS)");
        assertThat(text).contains("21/05/2026;Venta;Centro;cajero01;Detalle de prueba;Efectivo;1500,50");
        assertThat(text).contains("Total venta;1600,00");
        assertThat(text).contains("Total pago a proveedor;200,00");
        assertThat(text).contains("Cantidad de operaciones;3");
    }

    @Test
    void csv_currencyWithoutCents_hasNoDecimals() throws Exception {
        byte[] csv = export(List.of(op("SALE", "15990", "Centro")), "PYG", ReportFormat.CSV);
        assertThat(new String(csv, StandardCharsets.UTF_8)).contains(";15990\r\n");
    }

    @Test
    void context_showsUtcTimesInCompanyZone_andMoneyByCurrency() {
        ReportContext hn = new ReportContext("X", null, null, "HNL", ZoneId.of("America/Tegucigalpa"), LocalDateTime.now());
        assertThat(hn.dateTime(LocalDateTime.of(2026, 9, 8, 19, 43))).isEqualTo("08/09/2026 13:43");
        assertThat(hn.money(new BigDecimal("1234.5"))).isEqualTo("1,234.50");
        ReportContext cl = new ReportContext("X", null, null, "CLP", ZoneId.of("America/Santiago"), LocalDateTime.now());
        assertThat(cl.money(new BigDecimal("15990.4"))).isEqualTo("15,990");
    }

    private Row findRow(Sheet s, String firstCell) {
        for (Row r : s) {
            if (r.getCell(0) != null && r.getCell(0).getCellType() == CellType.STRING
                    && firstCell.equals(r.getCell(0).getStringCellValue())) {
                return r;
            }
        }
        throw new AssertionError("No se encontró la fila de encabezado '" + firstCell + "'");
    }
}
