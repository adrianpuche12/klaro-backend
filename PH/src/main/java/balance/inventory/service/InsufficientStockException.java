package balance.inventory.service;

import balance.common.exception.ConflictException;

/** No hay stock suficiente para la salida pedida (HTTP 409). Nunca deja la venta a medias. */
public class InsufficientStockException extends ConflictException {

    public InsufficientStockException(String productName) {
        super("Stock insuficiente" + (productName != null ? " para " + productName : ""));
    }
}
