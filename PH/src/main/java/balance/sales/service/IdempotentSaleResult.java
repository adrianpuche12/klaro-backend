package balance.sales.service;

/**
 * Resultado de registrar una venta con Idempotency-Key.
 *
 * @param body     la primera vez, el {@link balance.sales.dto.SaleResponseDTO} de la venta nueva;
 *                 en un reintento, el JSON exacto (String) que se devolvió la primera vez
 * @param replayed true si es un reintento y no se registró nada nuevo
 */
public record IdempotentSaleResult(Object body, boolean replayed) {
}
