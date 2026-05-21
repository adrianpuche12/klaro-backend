package balance.tenant.repository;

import balance.tenant.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findBySlug(String slug);
    List<Tenant> findAllByActiveTrue();
    boolean existsBySlug(String slug);
}
