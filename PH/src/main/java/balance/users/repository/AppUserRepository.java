package balance.users.repository;

import balance.users.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    List<AppUser> findByTenantIdOrderByFullNameAsc(Long tenantId);

    List<AppUser> findByStoreIdAndTenantIdOrderByFullNameAsc(Long storeId, Long tenantId);

    Optional<AppUser> findByUsernameAndTenantId(String username, Long tenantId);

    Optional<AppUser> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByUsernameAndTenantId(String username, Long tenantId);
}
