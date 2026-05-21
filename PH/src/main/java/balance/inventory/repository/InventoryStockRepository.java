package balance.inventory.repository;

import balance.inventory.model.InventoryStock;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
