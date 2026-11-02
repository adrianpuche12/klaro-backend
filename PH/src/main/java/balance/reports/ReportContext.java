package balance.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;

/**
 * Datos de la empresa y reglas de formato para un informe.
 *
 * Nada fijo de un cliente: nombre, dirección y teléfono salen de tenant_config; la moneda
 * define los decimales (CLP/PYG sin centavos, la mayoría con 2) y las horas técnicas
 * (guardadas en UTC) se muestran en la zona horaria de la empresa.
 */
public record ReportContext(
        String companyName,
        String address,
        String phone,
        String currency,
        ZoneId zone,
        LocalDateTime generatedAt) {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Decimales de la moneda según ISO 4217 (CLP/PYG = 0, USD/ARS = 2). Desconocida → 2. */
    public int fractionDigits() {
        try {
            int d = Currency.getInstance(currency).getDefaultFractionDigits();
            return d < 0 ? 2 : d;
        } catch (RuntimeException e) {
            return 2;
        }
    }

    /** Monto con separador de miles y los decimales de la moneda. */
    public String money(BigDecimal value) {
        if (value == null) return "";
        int digits = fractionDigits();
        DecimalFormatSymbols sym = new DecimalFormatSymbols();
        sym.setGroupingSeparator(',');
        sym.setDecimalSeparator('.');
        DecimalFormat f = new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits), sym);
        return f.format(value.setScale(digits, RoundingMode.HALF_UP));
    }

    /** Monto con el código de moneda adelante ("ARS 1,234.50"). */
    public String moneyWithCode(BigDecimal value) {
        String m = money(value);
        return currency == null || currency.isBlank() ? m : currency + " " + m;
    }

    public String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    /** Instante técnico guardado en UTC, mostrado en la zona de la empresa. */
    public String dateTime(LocalDateTime utc) {
        return utc == null ? "" : toCompany(utc).format(DATE_TIME);
    }

    public LocalDateTime toCompany(LocalDateTime utc) {
        return ZonedDateTime.of(utc, ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime();
    }

    public String generatedLabel() {
        return generatedAt.format(DATE_TIME);
    }
}
