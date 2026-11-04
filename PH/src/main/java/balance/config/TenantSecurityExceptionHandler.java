package balance.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * {@link SecurityException} es lo que lanza {@code TenantSecurityUtils.requireTenantId()} cuando el
 * token no trae empresa (claim tenant_id ausente o mal formado), y R2StorageService ante un
 * archivo de otra empresa. Es un acceso denegado, no un error del servidor: antes salía 500
 * (lo encontró CrossTenantHttpIT con un JWT válido sin tenant_id).
 */
@RestControllerAdvice
public class TenantSecurityExceptionHandler {

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Acceso denegado"));
    }
}
