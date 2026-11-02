package balance.reports;

import java.util.ArrayList;
import java.util.List;

/**
 * Informe como datos, independiente del formato: título, columnas tipadas, filas y un
 * bloque de totales. PDF, Excel y CSV se generan a partir de esto (ReportRenderer), así
 * cada informe nuevo es solo "armar la tabla".
 */
public class ReportTable {

    /** Tipo de columna: define alineación, formato numérico en Excel y cómo se muestra. */
    public enum ColType { TEXT, NUMBER, MONEY, DATE, DATETIME }

    public record Column(String header, ColType type, float width) {
        public static Column text(String h, float w)     { return new Column(h, ColType.TEXT, w); }
        public static Column number(String h, float w)   { return new Column(h, ColType.NUMBER, w); }
        public static Column money(String h, float w)    { return new Column(h, ColType.MONEY, w); }
        public static Column date(String h, float w)     { return new Column(h, ColType.DATE, w); }
        public static Column dateTime(String h, float w) { return new Column(h, ColType.DATETIME, w); }
    }

    /** Línea del bloque de totales: etiqueta + valor (monto o número). */
    public record Total(String label, Object value, ColType type) {}

    private final String title;
    private final String subtitle;
    private final List<Column> columns;
    private final List<List<Object>> rows = new ArrayList<>();
    private final List<Total> totals = new ArrayList<>();

    public ReportTable(String title, String subtitle, List<Column> columns) {
        this.title = title;
        this.subtitle = subtitle;
        this.columns = List.copyOf(columns);
    }

    /** Agrega una fila; los valores van en el mismo orden que las columnas. */
    public ReportTable row(Object... values) {
        if (values.length != columns.size()) {
            throw new IllegalArgumentException("La fila tiene " + values.length + " valores y hay " + columns.size() + " columnas");
        }
        rows.add(java.util.Arrays.asList(values));
        return this;
    }

    public ReportTable total(String label, Object value, ColType type) {
        totals.add(new Total(label, value, type));
        return this;
    }

    public String title()             { return title; }
    public String subtitle()          { return subtitle; }
    public List<Column> columns()     { return columns; }
    public List<List<Object>> rows()  { return rows; }
    public List<Total> totals()       { return totals; }
}
