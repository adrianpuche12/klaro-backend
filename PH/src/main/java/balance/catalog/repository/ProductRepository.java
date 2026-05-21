package balance.catalog.repository;

import balance.catalog.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByStoreIdOrderByNameAsc(Long storeId);

    List<Product> findByStoreIdAndActiveOrderByNameAsc(Long storeId, Boolean active);

    List<Product> findByStoreIdAndCategoryIdOrderByNameAsc(Long storeId, Long categoryId);

    @Query("SELECT p FROM Product p WHERE p.store.id = :storeId AND " +
           "(LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(p.sku)  LIKE LOWER(CONCAT('%', :search, '%')))")
    List<Product> searchByStoreId(@Param("storeId") Long storeId, @Param("search") String search);

    boolean existsBySkuAndStoreId(String sku, Long storeId);

    boolean existsBySkuAndStoreIdAndIdNot(String sku, Long storeId, Long id);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :categoryId")
    long countByCategoryId(@Param("categoryId") Long categoryId);

    /** Valida que el producto pertenezca al tenant (via store). */
    Optional<Product> findByIdAndStoreId(Long id, Long storeId);

    /** Valida que el store del producto coincida con el tenant. */
    boolean existsByIdAndTenantId(Long id, Long tenantId);
}
