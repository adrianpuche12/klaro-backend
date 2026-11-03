package balance.sales.repository;

import balance.sales.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Claves de idempotencia de ventas (V15). Todas las consultas filtran por tenant_id además
 * de RLS. TTL de 24 h: una clave más vieja se considera vencida.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    /**
     * Reclama la clave para esta transacción. Devuelve 1 si la clave es nueva (o estaba
     * vencida y se reinicia) y 0 si ya existe una vigente.
     *
     * Si otra transacción reclamó la misma clave y todavía no terminó, este INSERT espera a
     * que termine (índice único): si confirma, devuelve 0 y se usa su respuesta; si hace
     * rollback (p. ej. stock insuficiente), devuelve 1 y esta transacción registra la venta.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_keys (tenant_id, idempotency_key, request_hash, created_at)
            VALUES (:tenantId, :key, :requestHash, now())
            ON CONFLICT (tenant_id, idempotency_key) DO UPDATE
                SET request_hash = EXCLUDED.request_hash,
                    created_at   = EXCLUDED.created_at,
                    sale_id      = NULL,
                    response     = NULL
                WHERE idempotency_keys.created_at < now() - interval '24 hours'
            """, nativeQuery = true)
    int claim(@Param("tenantId") Long tenantId, @Param("key") String key, @Param("requestHash") String requestHash);

    Optional<IdempotencyKey> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);

    @Modifying
    @Query(value = "UPDATE idempotency_keys SET sale_id = :saleId, response = :response "
            + "WHERE tenant_id = :tenantId AND idempotency_key = :key", nativeQuery = true)
    int complete(@Param("tenantId") Long tenantId, @Param("key") String key,
                 @Param("saleId") Long saleId, @Param("response") String response);

    /** Borra las claves vencidas de la empresa (RLS + filtro explícito: nunca de otra). */
    @Modifying
    @Query(value = "DELETE FROM idempotency_keys WHERE tenant_id = :tenantId "
            + "AND created_at < now() - interval '24 hours'", nativeQuery = true)
    int purgeExpired(@Param("tenantId") Long tenantId);
}
