package balance.sales.repository;

import balance.common.enums.ShiftStatus;
import balance.sales.model.Shift;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByStoreIdAndStatusAndTenantId(Long storeId, ShiftStatus status, Long tenantId);

    List<Shift> findByStoreIdAndTenantIdOrderByOpenedAtDesc(Long storeId, Long tenantId);

    Page<Shift> findByStoreIdAndTenantIdOrderByOpenedAtDesc(Long storeId, Long tenantId, Pageable pageable);

    boolean existsByStoreIdAndStatusAndTenantId(Long storeId, ShiftStatus status, Long tenantId);

    /** Busca un shift por id validando que pertenezca al tenant. */
    Optional<Shift> findByIdAndTenantId(Long id, Long tenantId);

    /** Todos los turnos con un estado dado para el tenant (batch, evita N+1). */
    List<Shift> findByTenantIdAndStatus(Long tenantId, ShiftStatus status);
}
