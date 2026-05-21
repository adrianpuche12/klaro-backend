package balance.catalog.repository;

import balance.catalog.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Todas las categorías de un local (para construir el árbol en memoria). */
    List<Category> findAllByStoreId(Long storeId);

    /** Conteo de productos por lista de categorías — optimización para getTree(). */
    @Query("SELECT p.category.id, COUNT(p) FROM Product p WHERE p.category.id IN :ids GROUP BY p.category.id")
    List<Object[]> countProductsByCategoryIds(@Param("ids") List<Long> ids);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :categoryId")
    long countProductsByCategoryId(@Param("categoryId") Long categoryId);

    boolean existsByStoreIdAndParentIsNull(Long storeId);

    /** Verifica que el store pertenezca al tenant antes de cualquier operación. */
    boolean existsByStoreIdAndTenantId(Long storeId, Long tenantId);
}
