package balance.tenant.repository;

import balance.tenant.model.TenantConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantConfigRepository extends JpaRepository<TenantConfig, Long> {
}
