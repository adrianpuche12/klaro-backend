package balance.users.repository;

import balance.users.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findByTenantIdOrderByLevelAscNameAsc(Long tenantId);

    Optional<Role> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByTenantIdAndName(Long tenantId, String name);
}
