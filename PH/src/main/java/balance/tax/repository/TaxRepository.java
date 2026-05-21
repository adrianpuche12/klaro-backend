package balance.tax.repository;

import balance.tax.model.Tax;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaxRepository extends JpaRepository<Tax, Long> {

    List<Tax> findByTenantIdAndActiveTrue(Long tenantId);

    List<Tax> findByTenantId(Long tenantId);

    Optional<Tax> findByIdAndTenantId(Long id, Long tenantId);

    /** Impuestos globales (aplican a todo) activos del tenant. */
    @Query("SELECT t FROM Tax t WHERE t.tenantId = :tenantId AND t.active = true AND t.appliesTo = 'ALL'")
    List<Tax> findActiveTaxesForAll(@Param("tenantId") Long tenantId);

    /** Impuestos para una categoría específica. */
    @Query("SELECT t FROM Tax t WHERE t.tenantId = :tenantId AND t.active = true AND t.appliesTo = 'CATEGORY' AND t.categoryId = :categoryId")
    List<Tax> findActiveTaxesForCategory(@Param("tenantId") Long tenantId, @Param("categoryId") Long categoryId);

    /** Impuestos para un producto específico. */
    @Query("SELECT t FROM Tax t WHERE t.tenantId = :tenantId AND t.active = true AND t.appliesTo = 'PRODUCT' AND t.productId = :productId")
    List<Tax> findActiveTaxesForProduct(@Param("tenantId") Long tenantId, @Param("productId") Long productId);
}
