package balance.tenant.context;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Un recurso (ej. un local) no existe en la empresa del token: no existe o es de otra empresa,
 * y no se distingue a propósito.
 *
 * <p>Extiende {@link IllegalArgumentException} para no cambiar el contrato de los endpoints que
 * ya la capturan y responden 400 con el mensaje. Donde nadie la captura, Spring responde 404
 * (antes era un 500: lo encontró CrossTenantHttpIT pidiendo, con el token de una empresa, el
 * local de otra).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class RecursoNoEncontradoException extends IllegalArgumentException {

    public RecursoNoEncontradoException(String message) {
        super(message);
    }
}
