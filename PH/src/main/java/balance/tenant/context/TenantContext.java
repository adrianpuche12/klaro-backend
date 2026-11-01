package balance.tenant.context;

import java.util.function.Supplier;

/**
 * Almacena el tenantId del request actual en un ThreadLocal.
 * Se setea en TenantFilter al inicio de cada request y se limpia al final.
 *
 * Tenant 0 = administrador de plataforma (ver PlatformSecurity, SPRINT-16).
 */
public class TenantContext {

    /** Tenant reservado al administrador de plataforma (usuarios de Nilo, sin empresa). */
    public static final long PLATFORM_TENANT_ID = 0L;

    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    public static void setTenantId(Long tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static Long getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * Ejecuta {@code action} con otro tenant en el contexto y restaura el anterior al terminar.
     *
     * La conexión a la base toma el tenant al hacer checkout del pool (TenantAwareDataSource):
     * la acción tiene que abrir su PROPIA transacción (REQUIRES_NEW / TransactionTemplate) para que
     * RLS la evalúe con este tenant. Uso exclusivo del alta de empresas de la plataforma.
     */
    public static <T> T runAs(Long tenantId, Supplier<T> action) {
        Long previous = CURRENT_TENANT.get();
        CURRENT_TENANT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT_TENANT.remove();
            } else {
                CURRENT_TENANT.set(previous);
            }
        }
    }
}
