package balance.repository;

import balance.model.GastoAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface GastoAdminRepository extends JpaRepository<GastoAdmin, Long> {

    List<GastoAdmin> findByTenantIdOrderByFechaDesc(Long tenantId);

    @Query("SELECT g FROM GastoAdmin g WHERE g.tenantId = :tenantId " +
           "AND g.fecha BETWEEN :start AND :end ORDER BY g.fecha DESC")
    List<GastoAdmin> findByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    List<GastoAdmin> findByUsernameAndTenantId(String username, Long tenantId);

    @Query("SELECT SUM(g.monto) FROM GastoAdmin g WHERE g.tenantId = :tenantId " +
           "AND g.fecha BETWEEN :start AND :end")
    BigDecimal sumMontoByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT g FROM GastoAdmin g WHERE g.tenantId = :tenantId ORDER BY g.createdAt DESC")
    List<GastoAdmin> findLatestByTenantId(@Param("tenantId") Long tenantId);

    Optional<GastoAdmin> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByIdAndTenantId(Long id, Long tenantId);
}
