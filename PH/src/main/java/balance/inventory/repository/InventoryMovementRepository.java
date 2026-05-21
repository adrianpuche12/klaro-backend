package balance.inventory.repository;

import balance.inventory.model.InventoryMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    List<InventoryMovement> findByStoreIdAndTenantIdOrderByCreatedAtDesc(Long storeId, Long tenantId);

    List<InventoryMovement> findByProductIdAndStoreIdAndTenantIdOrderByCreatedAtDesc(
            Long productId, Long storeId, Long tenantId);

    void deleteByProductId(Long productId);
}
