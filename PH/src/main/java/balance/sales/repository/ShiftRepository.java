package balance.sales.repository;

import balance.common.enums.ShiftStatus;
import balance.sales.model.Shift;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByStoreIdAndStatusAndTenantId(Long storeId, ShiftStatus status, Long tenantId);

    List<Shift> findByStoreIdAndTenantIdOrderByOpenedAtDesc(Long storeId, Long tenantId);

    Page<Shift> findByStoreIdAndTenantIdOrderByOpenedAtDesc(Long storeId, Long tenantId, Pageable pageable);

    boolean existsByStoreIdAndStatusAndTenantId(Long storeId, ShiftStatus status, Long tenantId);

    /** Busca un shift por id validando que pertenezca al tenant. */
    Optional<Shift> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * Igual que {@link #findByIdAndTenantId} pero con SELECT ... FOR UPDATE: dos cierres
     * simultáneos del mismo turno se serializan y el segundo ve el estado CLOSED
     * (antes los dos podían pasar la validación y generar dos depósitos).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shift s where s.id = :id and s.tenantId = :tenantId")
    Optional<Shift> findForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /**
     * Serializa las aperturas de turno de un mismo local: toma el lock de la fila del local
     * (FOR NO KEY UPDATE, que no frena los INSERT de ventas/turnos que la referencian por FK).
     * Así la segunda apertura simultánea espera, ve el turno OPEN de la primera y responde
     * 409 con un mensaje claro. El índice único ux_shift_open (V14) es la red de seguridad.
     */
    @Query(value = "SELECT id FROM store WHERE id = :storeId AND tenant_id = :tenantId FOR NO KEY UPDATE",
           nativeQuery = true)
    Optional<Long> lockStoreForShiftOpening(@Param("storeId") Long storeId, @Param("tenantId") Long tenantId);

    /** Turnos del local cuyo código empieza con el prefijo (correlativo del día, ver ShiftService.generateCode). */
    @Query("select count(s) from Shift s where s.store.id = :storeId and s.tenantId = :tenantId and s.code like :prefix")
    long countByStoreAndCodePrefix(@Param("storeId") Long storeId, @Param("tenantId") Long tenantId,
                                   @Param("prefix") String prefix);

    /** Historial de un local filtrado por quien abrió el turno ("Mis ventas" del cajero). */
    Page<Shift> findByStoreIdAndTenantIdAndUsernameIgnoreCaseOrderByOpenedAtDesc(
            Long storeId, Long tenantId, String username, Pageable pageable);

    /** Todos los turnos con un estado dado para el tenant (batch, evita N+1). */
    List<Shift> findByTenantIdAndStatus(Long tenantId, ShiftStatus status);
}
