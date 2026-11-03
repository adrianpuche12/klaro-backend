package balance.sales.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reparto del cobro: el backend decide los montos; en MIXED el reparto tiene que cuadrar exacto. */
class PaymentSplitTest {

    private static final BigDecimal BASE = new BigDecimal("200.00");
    private static final BigDecimal RATE = new BigDecimal("0.03");

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // ── MIXED ────────────────────────────────────────────────────────────────

    @Test
    void mixed_queCuadra_aplicaRecargoSoloALaParteTarjeta() {
        PaymentSplit p = PaymentSplit.of("MIXED", BASE, RATE, d("100.00"), d("100.00"));

        assertThat(p.cashAmount()).isEqualByComparingTo("100.00");
        assertThat(p.cardBaseAmount()).isEqualByComparingTo("100.00");
        assertThat(p.cardAmount()).isEqualByComparingTo("103.00");
        assertThat(p.surcharge()).isEqualByComparingTo("3.00");
        assertThat(p.total()).isEqualByComparingTo("203.00");
    }

    @Test
    void mixed_queNoSumaElTotal_es400() {
        // El caso del Análisis Crítico: cobrar $1 por productos que valen $200.
        assertThatThrownBy(() -> PaymentSplit.of("MIXED", BASE, RATE, d("1.00"), d("0.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no cuadra");
    }

    @Test
    void mixed_toleranciaCero_unCentavoDeMenos_es400() {
        assertThatThrownBy(() -> PaymentSplit.of("MIXED", BASE, RATE, d("100.00"), d("99.99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no cuadra");
    }

    @Test
    void mixed_toleranciaCero_unCentavoDeMas_es400() {
        assertThatThrownBy(() -> PaymentSplit.of("MIXED", BASE, RATE, d("100.01"), d("100.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mixed_mismosMontosConOtraEscala_cuadra() {
        PaymentSplit p = PaymentSplit.of("MIXED", BASE, null, d("150"), d("50.0"));

        assertThat(p.total()).isEqualByComparingTo("200.00");
        assertThat(p.cashAmount().scale()).isEqualTo(2);
    }

    @Test
    void mixed_conMasDeDosDecimales_es400() {
        assertThatThrownBy(() -> PaymentSplit.of("MIXED", BASE, RATE, d("100.001"), d("99.999")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimales");
    }

    @Test
    void mixed_sinEfectivo_es400() {
        assertThatThrownBy(() -> PaymentSplit.of("MIXED", BASE, RATE, null, d("200.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("efectivo");
    }

    @Test
    void mixed_montoNegativo_es400() {
        assertThatThrownBy(() -> PaymentSplit.of("MIXED", BASE, RATE, d("-10.00"), d("210.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativo");
    }

    @Test
    void mixed_efectivoMayorAlTotal_es400() {
        assertThatThrownBy(() -> PaymentSplit.of("MIXED", BASE, RATE, d("250.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supera");
    }

    @Test
    void mixed_sinTarjeta_elBackendLaCalcula() {
        PaymentSplit p = PaymentSplit.of("MIXED", BASE, RATE, d("50.00"), null);

        assertThat(p.cardBaseAmount()).isEqualByComparingTo("150.00");
        assertThat(p.cardAmount()).isEqualByComparingTo("154.50");
        assertThat(p.total()).isEqualByComparingTo("204.50");
    }

    @Test
    void mixed_redondeoDelRecargo_aDosDecimales() {
        // 33.33 * 1.03 = 34.3299 -> 34.33
        PaymentSplit p = PaymentSplit.of("MIXED", d("100.00"), RATE, d("66.67"), d("33.33"));

        assertThat(p.cardAmount()).isEqualByComparingTo("34.33");
        assertThat(p.total()).isEqualByComparingTo("101.00");
        assertThat(p.total()).isEqualByComparingTo(p.cashAmount().add(p.cardAmount()));
    }

    // ── CASH / CARD: el cliente no decide montos ─────────────────────────────

    @Test
    void cash_ignoraLosMontosQueMandeElCliente() {
        PaymentSplit p = PaymentSplit.of("CASH", BASE, RATE, d("1.00"), d("1.00"));

        assertThat(p.cashAmount()).isEqualByComparingTo("200.00");
        assertThat(p.cardAmount()).isEqualByComparingTo("0.00");
        assertThat(p.total()).isEqualByComparingTo("200.00");
    }

    @Test
    void sinMetodo_esEfectivo() {
        assertThat(PaymentSplit.of(null, BASE, RATE, null, null).paymentMethod()).isEqualTo("CASH");
    }

    @Test
    void card_todoConRecargo() {
        PaymentSplit p = PaymentSplit.of("CARD", BASE, RATE, d("1.00"), null);

        assertThat(p.cashAmount()).isEqualByComparingTo("0.00");
        assertThat(p.cardAmount()).isEqualByComparingTo("206.00");
        assertThat(p.surcharge()).isEqualByComparingTo("6.00");
        assertThat(p.total()).isEqualByComparingTo("206.00");
    }

    @Test
    void card_sinRecargoConfigurado() {
        assertThat(PaymentSplit.of("CARD", BASE, BigDecimal.ZERO, null, null).total()).isEqualByComparingTo("200.00");
        assertThat(PaymentSplit.of("CARD", BASE, null, null, null).total()).isEqualByComparingTo("200.00");
    }
}
