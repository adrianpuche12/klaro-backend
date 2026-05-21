package balance.operations.service;

import balance.operations.dto.OperationDTO;
import balance.tenant.service.TenantConfigService;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired private OperationsV3Service operationsService;
    @Autowired private TenantConfigService tenantConfigService;

    public byte[] generatePDF(LocalDate from, LocalDate to, String typeFilter, Long storeId) throws IOException {
        List<OperationDTO> operations = operationsService.getAllForExport(from, to, typeFilter, storeId);
        var config   = tenantConfigService.getConfig();
        String currency = config.getCurrency()    != null ? config.getCurrency()    : "L";
        String company  = config.getCompanyName() != null ? config.getCompanyName() : "Klaro";

        ByteArrayOutputStream baos   = new ByteArrayOutputStream();
        PdfDocument           pdfDoc = new PdfDocument(new PdfWriter(baos));
        Document              doc    = new Document(pdfDoc);

        doc.add(new Paragraph(company).setFontSize(18).setBold().setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("Reporte de Operaciones").setFontSize(14).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("Período: " + fmt(from) + " al " + fmt(to))
                .setFontSize(10).setFontColor(ColorConstants.GRAY).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("Generado: " + LocalDate.now().format(DATE_FMT))
                .setFontSize(9).setFontColor(ColorConstants.GRAY).setTextAlignment(TextAlignment.CENTER));
        doc.add(new Paragraph("\n"));

        BigDecimal total = operations.stream()
                .map(o -> o.getAmount() != null ? o.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        doc.add(new Paragraph("Total operaciones: " + operations.size()).setFontSize(11).setBold());
        doc.add(new Paragraph("Monto total: " + currency + " " + total).setFontSize(11).setBold());
        doc.add(new Paragraph("\n"));

        // iText Table — nombre completamente calificado para evitar conflicto con Apache POI
        com.itextpdf.layout.element.Table table =
                new com.itextpdf.layout.element.Table(
                        UnitValue.createPercentArray(new float[]{15, 12, 20, 25, 18}))
                        .useAllAvailableWidth();

        for (String h : new String[]{"Tipo", "Fecha", "Local", "Descripción", "Monto"}) {
            table.addHeaderCell(
                    new com.itextpdf.layout.element.Cell()
                            .add(new Paragraph(h).setBold())
                            .setBackgroundColor(ColorConstants.LIGHT_GRAY));
        }

        for (OperationDTO op : operations) {
            table.addCell(safe(op.getType()));
            table.addCell(op.getDate() != null ? op.getDate().format(DATE_FMT) : "");
            table.addCell(safe(op.getStoreName(), "—"));
            table.addCell(safe(op.getDescription()));
            table.addCell(currency + " " + safe(op.getAmount() != null ? op.getAmount().toString() : null, "0.00"));
        }

        doc.add(table);
        doc.close();
        return baos.toByteArray();
    }

    public byte[] generateExcel(LocalDate from, LocalDate to, String typeFilter, Long storeId) throws IOException {
        List<OperationDTO> operations = operationsService.getAllForExport(from, to, typeFilter, storeId);
        var    config   = tenantConfigService.getConfig();
        String currency = config.getCurrency() != null ? config.getCurrency() : "L";

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Operaciones");

            CellStyle headerStyle = workbook.createCellStyle();
            Font      headerFont  = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row      headerRow = sheet.createRow(0);
            String[] headers   = {"ID", "Tipo", "Fecha", "Local", "Usuario",
                                   "Descripción", "Monto (" + currency + ")", "Estado", "Método Pago"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (OperationDTO op : operations) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(op.getId()     != null ? op.getId()            : 0);
                row.createCell(1).setCellValue(safe(op.getType()));
                row.createCell(2).setCellValue(op.getDate()   != null ? op.getDate().format(DATE_FMT) : "");
                row.createCell(3).setCellValue(safe(op.getStoreName()));
                row.createCell(4).setCellValue(safe(op.getUsername()));
                row.createCell(5).setCellValue(safe(op.getDescription()));
                if (op.getAmount() != null) row.createCell(6).setCellValue(op.getAmount().doubleValue());
                row.createCell(7).setCellValue(safe(op.getStatus()));
                row.createCell(8).setCellValue(safe(op.getPaymentMethod()));
            }

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    public byte[] generateCSV(LocalDate from, LocalDate to, String typeFilter, Long storeId) throws IOException {
        List<OperationDTO> operations = operationsService.getAllForExport(from, to, typeFilter, storeId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(baos, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.withHeader("ID", "Tipo", "Fecha", "Local", "Usuario",
                        "Descripción", "Monto", "Estado", "Método Pago"))) {
            for (OperationDTO op : operations) {
                printer.printRecord(
                        op.getId(), safe(op.getType()),
                        op.getDate() != null ? op.getDate().format(DATE_FMT) : "",
                        safe(op.getStoreName()), safe(op.getUsername()),
                        safe(op.getDescription()), op.getAmount(),
                        safe(op.getStatus()), safe(op.getPaymentMethod()));
            }
        }
        return baos.toByteArray();
    }

    private String fmt(LocalDate d) { return d != null ? d.format(DATE_FMT) : "—"; }
    private String safe(String s) { return s != null ? s : ""; }
    private String safe(String s, String def) { return s != null ? s : def; }
}
