package balance.repository;

import balance.model.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findByTenantId(Long tenantId);

    List<Store> findByTenantIdAndActive(Long tenantId, Boolean active);

    /** Busca un store por id SOLO si pertenece al tenant — evita acceso cruzado. */
    Optional<Store> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByIdAndTenantId(Long id, Long tenantId);
}
