package balance.common.exception;

/**
 * El pedido es válido pero choca con el estado actual (HTTP 409): stock insuficiente,
 * turno ya abierto en el local, Idempotency-Key reutilizada con otro contenido.
 *
 * No extiende IllegalStateException/IllegalArgumentException a propósito: los
 * controllers las traducen a 400, y esto tiene que llegar como 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
