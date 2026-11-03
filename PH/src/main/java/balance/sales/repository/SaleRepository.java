package balance.sales.repository;

import balance.common.enums.SaleStatus;
import balance.sales.model.Sale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    List<Sale> findByShiftIdAndTenantIdOrderByCreatedAtDesc(Long shiftId, Long tenantId);

    /** El estado es el enum (antes String: Hibernate 6 rechaza el parámetro, lo detectó SaleRepositoryIT al correr en Failsafe). */
    List<Sale> findByShiftIdAndStatusAndTenantId(Long shiftId, SaleStatus status, Long tenantId);

    /** Busca una venta por id validando que pertenezca al tenant. */
    Optional<Sale> findByIdAndTenantId(Long id, Long tenantId);

    // COALESCE en vez de "(:from IS NULL OR …)": con ese patrón Postgres no puede inferir el
    // tipo del parámetro ("could not determine data type of parameter") y la consulta fallaba
    // siempre. Con COALESCE el tipo sale de la columna; null sigue significando "sin límite".
    @Query("SELECT s FROM Sale s WHERE s.store.id = :storeId AND s.tenantId = :tenantId " +
           "AND s.saleDate >= COALESCE(:from, s.saleDate) AND s.saleDate <= COALESCE(:to, s.saleDate) " +
           "ORDER BY s.createdAt DESC")
    List<Sale> findByStoreIdAndTenantIdAndDateRange(
            @Param("storeId") Long storeId,
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT s FROM Sale s WHERE s.store.id = :storeId AND s.tenantId = :tenantId " +
           "AND s.saleDate >= :from AND s.saleDate <= :to ORDER BY s.createdAt DESC")
    List<Sale> findByStoreIdAndTenantIdAndDateRangeStrict(
            @Param("storeId") Long storeId,
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT s FROM Sale s WHERE s.shift.id = :shiftId AND s.tenantId = :tenantId AND s.status = 'OPEN'")
    List<Sale> findOpenByShiftIdAndTenantId(
            @Param("shiftId") Long shiftId,
            @Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(s) FROM Sale s WHERE s.shift.id = :shiftId AND s.tenantId = :tenantId AND s.status = 'OPEN'")
    long countOpenByShiftIdAndTenantId(
            @Param("shiftId") Long shiftId,
            @Param("tenantId") Long tenantId);

    /** Todas las ventas del tenant en un rango de fechas (batch, evita N+1). */
    @Query("SELECT s FROM Sale s WHERE s.tenantId = :tenantId AND s.saleDate >= :from AND s.saleDate <= :to")
    List<Sale> findByTenantIdAndDateRangeStrict(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Ventas abiertas de varios turnos en una sola query (batch, evita N+1). */
    @Query("SELECT s FROM Sale s WHERE s.shift.id IN :shiftIds AND s.tenantId = :tenantId AND s.status = 'OPEN'")
    List<Sale> findOpenByShiftIdsAndTenantId(
            @Param("shiftIds") List<Long> shiftIds,
            @Param("tenantId") Long tenantId);
}
