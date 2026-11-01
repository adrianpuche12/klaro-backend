package balance.tenant.service;

import balance.tenant.context.TenantContext;
import balance.tenant.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Indica si una empresa está activa (SPRINT-16). Una empresa suspendida por la plataforma
 * no puede operar: TenantFilter rechaza sus pedidos con 403 TENANT_SUSPENDED.
 *
 * Se consulta en cada request, con caché de {@link #TTL} por empresa para no ir a la base cada vez.
 * El toggle de la plataforma invalida la caché de esa empresa en este nodo.
 *
 * Reglas:
 * <ul>
 *   <li>Tenant 0 (plataforma): siempre activo.</li>
 *   <li>Empresa sin fila en {@code tenants}: activa (datos previos al alta de empresas; V12 crea las filas faltantes).</li>
 * </ul>
 * La consulta corre con el tenant del request en la sesión: RLS le deja ver solo su propia fila.
 */
@Service
public class TenantStatusService {

    static final Duration TTL = Duration.ofSeconds(30);

    private record Entry(boolean active, Instant expiresAt) {}

    private final TenantRepository tenantRepository;
    private final Clock clock;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public TenantStatusService(TenantRepository tenantRepository) {
        this(tenantRepository, Clock.systemUTC());
    }

    TenantStatusService(TenantRepository tenantRepository, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.clock = clock;
    }

    public boolean isActive(Long tenantId) {
        if (tenantId == null || tenantId == TenantContext.PLATFORM_TENANT_ID) {
            return true;
        }
        Instant now = clock.instant();
        Entry cached = cache.get(tenantId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.active();
        }
        boolean active = tenantRepository.findById(tenantId)
                .map(t -> Boolean.TRUE.equals(t.getActive()))
                .orElse(true);
        cache.put(tenantId, new Entry(active, now.plus(TTL)));
        return active;
    }

    public void evict(Long tenantId) {
        cache.remove(tenantId);
    }
}
