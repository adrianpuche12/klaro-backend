package balance.tenant.context;

import balance.model.Store;
import balance.repository.StoreRepository;

/**
 * Utilidades de seguridad multi-tenant.
 * Todos los servicios deben usar estos métodos para validar acceso.
 */
public class TenantSecurityUtils {

    /**
     * Retorna el tenantId del contexto actual.
     * Lanza excepción si no hay tenant en el contexto (request sin header X-Tenant-ID).
     */
    public static Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new SecurityException("No tenant context — header X-Tenant-ID requerido");
        }
        return tenantId;
    }

    /**
     * Valida que un store pertenezca al tenant actual.
     * Lanza excepción si el store no existe o pertenece a otro tenant.
     * Usar antes de cualquier operación que recibe storeId del cliente.
     */
    public static Store requireStore(Long storeId, Long tenantId, StoreRepository storeRepository) {
        return storeRepository.findByIdAndTenantId(storeId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Local no encontrado o no pertenece a este tenant"));
    }
}
