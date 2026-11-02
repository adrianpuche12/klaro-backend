package balance.reports;

/** Formatos de descarga. */
public enum ReportFormat {
    PDF("application/pdf", "pdf"),
    EXCEL("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    CSV("text/csv; charset=UTF-8", "csv");

    private final String contentType;
    private final String extension;

    ReportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() { return contentType; }
    public String extension()   { return extension; }

    /** "pdf", "excel", "xlsx", "csv" (sin importar mayúsculas). Desconocido → PDF. */
    public static ReportFormat parse(String value) {
        if (value == null) return PDF;
        return switch (value.trim().toUpperCase()) {
            case "EXCEL", "XLSX", "XLS" -> EXCEL;
            case "CSV" -> CSV;
            default -> PDF;
        };
    }
}
