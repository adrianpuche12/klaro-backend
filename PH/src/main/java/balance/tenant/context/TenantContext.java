package balance.tenant.context;

/**
 * Almacena el tenantId del request actual en un ThreadLocal.
 * Se setea en TenantFilter al inicio de cada request y se limpia al final.
 */
public class TenantContext {

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
}
