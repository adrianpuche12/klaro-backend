package balance.inventory.repository;

import balance.inventory.model.InventoryStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryStockRepository extends JpaRepository<InventoryStock, Long> {

    @Query("SELECT s FROM InventoryStock s JOIN FETCH s.product p JOIN FETCH s.product.store JOIN FETCH s.store " +
           "LEFT JOIN FETCH p.category cat LEFT JOIN FETCH cat.parent " +
           "WHERE s.store.id = :storeId AND s.tenantId = :tenantId ORDER BY p.name ASC")
    List<InventoryStock> findByStoreIdAndTenantIdOrderByProductNameAsc(
            @Param("storeId") Long storeId, @Param("tenantId") Long tenantId);

    Optional<InventoryStock> findByProductIdAndStoreIdAndTenantId(Long productId, Long storeId, Long tenantId);

    @Query("SELECT s FROM InventoryStock s WHERE s.store.id = :storeId AND s.tenantId = :tenantId " +
           "AND s.product.minStock > 0 AND s.quantity <= s.product.minStock")
    List<InventoryStock> findLowStockByStoreIdAndTenantId(
            @Param("storeId") Long storeId, @Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(s) FROM InventoryStock s WHERE s.store.id = :storeId AND s.tenantId = :tenantId " +
           "AND s.product.minStock > 0 AND s.quantity <= s.product.minStock")
    long countLowStockByStoreIdAndTenantId(
            @Param("storeId") Long storeId, @Param("tenantId") Long tenantId);

    void deleteByProductId(Long productId);

    // ── Movimientos atómicos (sin leer-modificar-escribir) ───────────────────
    // Dos cajas que venden el mismo producto a la vez se serializan en el lock de la
    // fila que toma el UPDATE: la segunda reevalúa "quantity >= :qty" con el valor ya
    // descontado por la primera. Así no hay actualizaciones perdidas ni sobreventa.
    // Todas filtran por tenant_id además de RLS.

    /** Crea la fila de stock en 0 si no existe (dos altas simultáneas no chocan). */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO inventory_stock (tenant_id, product_id, store_id, quantity, updated_at) "
            + "VALUES (:tenantId, :productId, :storeId, 0, now()) "
            + "ON CONFLICT (product_id, store_id) DO NOTHING", nativeQuery = true)
    int insertIfMissing(@Param("tenantId") Long tenantId, @Param("productId") Long productId,
                        @Param("storeId") Long storeId);

    /** Descuenta solo si alcanza. 0 filas = stock insuficiente (o sin fila). */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE inventory_stock SET quantity = quantity - :qty, updated_at = now() "
            + "WHERE product_id = :productId AND store_id = :storeId AND tenant_id = :tenantId "
            + "AND quantity >= :qty", nativeQuery = true)
    int decrementIfAvailable(@Param("tenantId") Long tenantId, @Param("productId") Long productId,
                             @Param("storeId") Long storeId, @Param("qty") int qty);

    /** Suma cantidad (entradas, cancelaciones). */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE inventory_stock SET quantity = quantity + :qty, updated_at = now() "
            + "WHERE product_id = :productId AND store_id = :storeId AND tenant_id = :tenantId", nativeQuery = true)
    int increment(@Param("tenantId") Long tenantId, @Param("productId") Long productId,
                  @Param("storeId") Long storeId, @Param("qty") int qty);
}
