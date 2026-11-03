package balance.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cómo se reparte el cobro de una venta entre efectivo y tarjeta. Lo decide SIEMPRE el
 * backend a partir del total de los ítems (+ impuestos); el cliente solo propone el
 * reparto en un pago MIXED y el backend lo acepta únicamente si cuadra exacto.
 *
 * Escala: 2 decimales, la de las columnas de montos (sales.* NUMERIC(12,2)). Tolerancia 0
 * en esa escala: un MIXED con efectivo + tarjeta distinto del total por un centavo es 400.
 *
 * @param cashAmount     parte en efectivo
 * @param cardBaseAmount parte en tarjeta ANTES del recargo
 * @param cardAmount     parte en tarjeta cobrada (con recargo), lo que se guarda en sales.card_amount
 * @param surcharge      recargo por tarjeta (cardAmount - cardBaseAmount)
 * @param total          total cobrado = cashAmount + cardAmount
 */
public record PaymentSplit(String paymentMethod,
                           BigDecimal cashAmount,
                           BigDecimal cardBaseAmount,
                           BigDecimal cardAmount,
                           BigDecimal surcharge,
                           BigDecimal total) {

    public static final int MONEY_SCALE = 2;

    /**
     * @param paymentMethod  CASH (default), CARD o MIXED
     * @param baseTotal      subtotal + impuestos, calculado por el backend desde los ítems
     * @param surchargeRate  recargo por tarjeta de tenant_config (null o 0 = sin recargo)
     * @param requestedCash  efectivo que propone el cliente (solo MIXED, obligatorio)
     * @param requestedCard  tarjeta SIN recargo que propone el cliente (solo MIXED, opcional:
     *                       si viene, efectivo + tarjeta tiene que ser exactamente baseTotal;
     *                       si no viene, el backend la calcula como baseTotal - efectivo)
     * @throws IllegalArgumentException si el reparto MIXED no es válido o no cuadra (400)
     */
    public static PaymentSplit of(String paymentMethod, BigDecimal baseTotal, BigDecimal surchargeRate,
                                  BigDecimal requestedCash, BigDecimal requestedCard) {
        String method = paymentMethod != null ? paymentMethod : "CASH";
        BigDecimal base = baseTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE);

        switch (method) {
            case "CARD": {
                BigDecimal card = applyCardSurcharge(base, surchargeRate);
                return new PaymentSplit(method, zero, base, card, card.subtract(base), card);
            }
            case "MIXED": {
                if (requestedCash == null) {
                    throw new IllegalArgumentException("En un pago MIXED hay que indicar el monto en efectivo");
                }
                BigDecimal cash = money(requestedCash, "efectivo");
                if (cash.compareTo(base) > 0) {
                    throw new IllegalArgumentException("El efectivo (" + cash + ") supera el total (" + base + ")");
                }
                BigDecimal cardBase = base.subtract(cash);
                if (requestedCard != null) {
                    BigDecimal card = money(requestedCard, "tarjeta");
                    if (cash.add(card).compareTo(base) != 0) {
                        throw new IllegalArgumentException("El pago MIXED no cuadra: efectivo " + cash
                                + " + tarjeta " + card + " = " + cash.add(card) + ", y el total es " + base);
                    }
                }
                BigDecimal card = applyCardSurcharge(cardBase, surchargeRate);
                return new PaymentSplit(method, cash, cardBase, card, card.subtract(cardBase), cash.add(card));
            }
            default:
                // CASH (y cualquier otro método heredado): todo en efectivo, sin recargo.
                return new PaymentSplit(method, base, zero, zero, zero, base);
        }
    }

    /** amount * (1 + rate) a 2 decimales, o amount sin cambios si rate es null/0. */
    static BigDecimal applyCardSurcharge(BigDecimal amount, BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) return amount;
        return amount.multiply(BigDecimal.ONE.add(rate)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal amount, String label) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("El monto en " + label + " no puede ser negativo");
        }
        if (amount.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new IllegalArgumentException("El monto en " + label + " tiene más de " + MONEY_SCALE + " decimales");
        }
        return amount.setScale(MONEY_SCALE);
    }
}
