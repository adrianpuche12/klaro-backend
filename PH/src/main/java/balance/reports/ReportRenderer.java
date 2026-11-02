package balance.reports;

import balance.reports.ReportTable.ColType;
import balance.reports.ReportTable.Column;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Genera PDF, Excel y CSV a partir de un {@link ReportTable}.
 *
 * - PDF (OpenPDF): encabezado con los datos de la empresa, tabla y bloque de totales.
 *   Horizontal si hay muchas columnas.
 * - Excel (POI streaming, SXSSF): montos y fechas como valores REALES (se pueden sumar y
 *   filtrar), con el formato de la moneda; no carga todo el libro en memoria.
 * - CSV: UTF-8 con BOM y separador ";" con coma decimal, que es lo que espera Excel en
 *   Sudamérica (sin BOM los acentos se rompen; con "," las columnas se mezclan).
 */
@Component
public class ReportRenderer {

    public byte[] render(ReportTable table, ReportContext ctx, ReportFormat format) throws IOException {
        return switch (format) {
            case PDF -> pdf(table, ctx);
            case EXCEL -> excel(table, ctx);
            case CSV -> csv(table, ctx);
        };
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    private static final Color HEADER_BG = new Color(235, 232, 225);

    byte[] pdf(ReportTable t, ReportContext ctx) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Rectangle page = t.columns().size() > 6 ? PageSize.A4.rotate() : PageSize.A4;
        Document doc = new Document(page, 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font company = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 8);

        doc.add(new Paragraph(nz(ctx.companyName(), "Mi empresa"), company));
        String contact = joinNonBlank(" · ", ctx.address(), ctx.phone());
        if (!contact.isEmpty()) doc.add(new Paragraph(contact, small));
        doc.add(new Paragraph(" ", small));
        doc.add(new Paragraph(t.title(), title));
        if (t.subtitle() != null && !t.subtitle().isBlank()) doc.add(new Paragraph(t.subtitle(), small));
        doc.add(new Paragraph("Generado: " + ctx.generatedLabel()
                + (ctx.currency() == null || ctx.currency().isBlank() ? "" : " · Moneda: " + ctx.currency()), small));
        doc.add(new Paragraph(" ", small));

        List<Column> cols = t.columns();
        PdfPTable grid = new PdfPTable(cols.size());
        grid.setWidthPercentage(100);
        float[] widths = new float[cols.size()];
        for (int i = 0; i < cols.size(); i++) widths[i] = cols.get(i).width();
        grid.setWidths(widths);
        grid.setHeaderRows(1);
        for (Column c : cols) {
            PdfPCell cell = new PdfPCell(new Phrase(c.header(), head));
            cell.setBackgroundColor(HEADER_BG);
            cell.setHorizontalAlignment(alignOf(c.type()));
            cell.setPadding(4);
            grid.addCell(cell);
        }
        if (t.rows().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("Sin datos para el período elegido.", body));
            empty.setColspan(cols.size());
            empty.setPadding(6);
            grid.addCell(empty);
        }
        for (List<Object> r : t.rows()) {
            for (int i = 0; i < cols.size(); i++) {
                PdfPCell cell = new PdfPCell(new Phrase(text(r.get(i), cols.get(i).type(), ctx), body));
                cell.setHorizontalAlignment(alignOf(cols.get(i).type()));
                cell.setPadding(3);
                grid.addCell(cell);
            }
        }
        doc.add(grid);

        if (!t.totals().isEmpty()) {
            doc.add(new Paragraph(" ", small));
            PdfPTable tot = new PdfPTable(2);
            tot.setWidthPercentage(45);
            tot.setHorizontalAlignment(Element.ALIGN_RIGHT);
            for (ReportTable.Total total : t.totals()) {
                PdfPCell l = new PdfPCell(new Phrase(total.label(), head));
                l.setBorder(Rectangle.NO_BORDER);
                PdfPCell v = new PdfPCell(new Phrase(text(total.value(), total.type(), ctx), head));
                v.setBorder(Rectangle.NO_BORDER);
                v.setHorizontalAlignment(Element.ALIGN_RIGHT);
                tot.addCell(l);
                tot.addCell(v);
            }
            doc.add(tot);
        }
        doc.close();
        return out.toByteArray();
    }

    private int alignOf(ColType type) {
        return switch (type) {
            case MONEY, NUMBER -> Element.ALIGN_RIGHT;
            case DATE, DATETIME -> Element.ALIGN_CENTER;
            default -> Element.ALIGN_LEFT;
        };
    }

    // ── Excel ────────────────────────────────────────────────────────────────

    byte[] excel(ReportTable t, ReportContext ctx) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200)) {
            Sheet sheet = wb.createSheet(sheetName(t.title()));
            CreationHelper ch = wb.getCreationHelper();
            int digits = ctx.fractionDigits();

            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle titleStyle = wb.createCellStyle();
            titleStyle.setFont(bold);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle moneyStyle = wb.createCellStyle();
            moneyStyle.setDataFormat(ch.createDataFormat().getFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits)));
            CellStyle numberStyle = wb.createCellStyle();
            numberStyle.setDataFormat(ch.createDataFormat().getFormat("#,##0.##"));
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(ch.createDataFormat().getFormat("dd/mm/yyyy"));
            dateStyle.setAlignment(HorizontalAlignment.CENTER);
            CellStyle dateTimeStyle = wb.createCellStyle();
            dateTimeStyle.setDataFormat(ch.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
            dateTimeStyle.setAlignment(HorizontalAlignment.CENTER);
            CellStyle moneyBold = wb.createCellStyle();
            moneyBold.cloneStyleFrom(moneyStyle);
            moneyBold.setFont(bold);

            int r = 0;
            sheet.createRow(r++).createCell(0).setCellValue(nz(ctx.companyName(), "Mi empresa"));
            sheet.getRow(0).getCell(0).setCellStyle(titleStyle);
            sheet.createRow(r++).createCell(0).setCellValue(t.title());
            if (t.subtitle() != null && !t.subtitle().isBlank()) sheet.createRow(r++).createCell(0).setCellValue(t.subtitle());
            sheet.createRow(r++).createCell(0).setCellValue("Generado: " + ctx.generatedLabel()
                    + (ctx.currency() == null || ctx.currency().isBlank() ? "" : " · Moneda: " + ctx.currency()));
            r++;

            List<Column> cols = t.columns();
            int headerRow = r;
            Row hr = sheet.createRow(r++);
            for (int i = 0; i < cols.size(); i++) {
                Cell c = hr.createCell(i);
                String h = cols.get(i).header();
                if (cols.get(i).type() == ColType.MONEY && ctx.currency() != null && !ctx.currency().isBlank()) {
                    h = h + " (" + ctx.currency() + ")";
                }
                c.setCellValue(h);
                c.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, Math.min(255, Math.max(10, Math.round(cols.get(i).width() * 1.6f))) * 256);
            }
            for (List<Object> row : t.rows()) {
                Row xr = sheet.createRow(r++);
                for (int i = 0; i < cols.size(); i++) {
                    Object v = row.get(i);
                    if (v == null) continue;
                    Cell c = xr.createCell(i);
                    switch (cols.get(i).type()) {
                        case MONEY -> { c.setCellValue(toBig(v).setScale(digits, RoundingMode.HALF_UP).doubleValue()); c.setCellStyle(moneyStyle); }
                        case NUMBER -> { c.setCellValue(toBig(v).doubleValue()); c.setCellStyle(numberStyle); }
                        case DATE -> { if (v instanceof LocalDate d) { c.setCellValue(d); c.setCellStyle(dateStyle); } else c.setCellValue(v.toString()); }
                        case DATETIME -> { if (v instanceof LocalDateTime d) { c.setCellValue(ctx.toCompany(d)); c.setCellStyle(dateTimeStyle); } else c.setCellValue(v.toString()); }
                        default -> c.setCellValue(String.valueOf(v));
                    }
                }
            }
            if (!t.totals().isEmpty()) {
                r++;
                for (ReportTable.Total total : t.totals()) {
                    Row tr = sheet.createRow(r++);
                    Cell l = tr.createCell(0);
                    l.setCellValue(total.label());
                    l.setCellStyle(titleStyle);
                    Cell v = tr.createCell(1);
                    if (total.type() == ColType.MONEY) {
                        v.setCellValue(toBig(total.value()).setScale(digits, RoundingMode.HALF_UP).doubleValue());
                        v.setCellStyle(moneyBold);
                    } else {
                        v.setCellValue(toBig(total.value()).doubleValue());
                    }
                }
            }
            sheet.createFreezePane(0, headerRow + 1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        }
    }

    private String sheetName(String title) {
        String s = title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    byte[] csv(ReportTable t, ReportContext ctx) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // BOM UTF-8 para Excel
        List<Column> cols = t.columns();
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setDelimiter(';').build();
        try (Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8); CSVPrinter p = new CSVPrinter(w, fmt)) {
            Object[] headers = new Object[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                String h = cols.get(i).header();
                if (cols.get(i).type() == ColType.MONEY && ctx.currency() != null && !ctx.currency().isBlank()) {
                    h = h + " (" + ctx.currency() + ")";
                }
                headers[i] = h;
            }
            p.printRecord(headers);
            for (List<Object> row : t.rows()) {
                Object[] values = new Object[cols.size()];
                for (int i = 0; i < cols.size(); i++) values[i] = csvValue(row.get(i), cols.get(i).type(), ctx);
                p.printRecord(values);
            }
            if (!t.totals().isEmpty()) {
                p.println();
                for (ReportTable.Total total : t.totals()) {
                    p.printRecord(total.label(), csvValue(total.value(), total.type(), ctx));
                }
            }
        }
        return out.toByteArray();
    }

    /** Números con coma decimal y sin separador de miles (lo que Excel en español lee como número). */
    private String csvValue(Object v, ColType type, ReportContext ctx) {
        if (v == null) return "";
        return switch (type) {
            case MONEY -> toBig(v).setScale(ctx.fractionDigits(), RoundingMode.HALF_UP).toPlainString().replace('.', ',');
            case NUMBER -> toBig(v).stripTrailingZeros().toPlainString().replace('.', ',');
            case DATE -> v instanceof LocalDate d ? ctx.date(d) : v.toString();
            case DATETIME -> v instanceof LocalDateTime d ? ctx.dateTime(d) : v.toString();
            default -> String.valueOf(v);
        };
    }

    // ── Comunes ──────────────────────────────────────────────────────────────

    /** Texto para PDF. */
    String text(Object v, ColType type, ReportContext ctx) {
        if (v == null) return "";
        return switch (type) {
            case MONEY -> ctx.money(toBig(v));
            case NUMBER -> toBig(v).stripTrailingZeros().toPlainString();
            case DATE -> v instanceof LocalDate d ? ctx.date(d) : v.toString();
            case DATETIME -> v instanceof LocalDateTime d ? ctx.dateTime(d) : v.toString();
            default -> String.valueOf(v);
        };
    }

    private static BigDecimal toBig(Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private static String nz(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(sep);
            sb.append(p.trim());
        }
        return sb.toString();
    }
}
