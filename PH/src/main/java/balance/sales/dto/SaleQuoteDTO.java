package balance.sales.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta de POST /api/v2/shifts/{shiftId}/sales/quote: el precio de una venta calculado
 * con el MISMO código que POST /api/v2/shifts/{shiftId}/sales, sin registrar nada.
 * El total de la cotización es exactamente el total que se cobra al confirmar la venta
 * con el mismo cuerpo (mientras no cambien precios, impuestos ni el recargo).
 */
@Getter
@AllArgsConstructor
public class SaleQuoteDTO {

    /** Suma de los ítems (precio x cantidad). */
    private BigDecimal subtotal;
    /** Impuestos de la empresa sobre los ítems (lo que la venta guarda en "isv"). */
    private BigDecimal tax;
    /** subtotal + tax: lo que se reparte entre efectivo y tarjeta. */
    private BigDecimal baseTotal;
    /** Tasa de recargo por tarjeta de la empresa (null = sin recargo). */
    private BigDecimal cardSurchargeRate;
    /** Recargo por tarjeta aplicado (0 si el pago es en efectivo). */
    private BigDecimal surcharge;
    /** Total a cobrar = baseTotal + surcharge. */
    private BigDecimal total;
    private String paymentMethod;
    private BigDecimal cashAmount;
    /** Parte en tarjeta sin recargo. */
    private BigDecimal cardBaseAmount;
    /** Parte en tarjeta con recargo (lo que se cobra en la terminal). */
    private BigDecimal cardAmount;
    private List<Line> items;

    @Getter
    @AllArgsConstructor
    public static class Line {
        private Long productId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
        private BigDecimal tax;
    }
}
