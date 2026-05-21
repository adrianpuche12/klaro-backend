package balance.tenant.service;

import balance.tenant.dto.TenantRequestDTO;
import balance.tenant.dto.TenantResponseDTO;
import balance.tenant.model.Tenant;
import balance.tenant.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class TenantService {

    @Autowired
    private TenantRepository tenantRepository;

    public List<TenantResponseDTO> findAll() {
        return tenantRepository.findAll()
                .stream().map(TenantResponseDTO::from).toList();
    }

    public List<TenantResponseDTO> findAllActive() {
        return tenantRepository.findAllByActiveTrue()
                .stream().map(TenantResponseDTO::from).toList();
    }

    public Optional<TenantResponseDTO> findById(Long id) {
        return tenantRepository.findById(id).map(TenantResponseDTO::from);
    }

    public Optional<TenantResponseDTO> findBySlug(String slug) {
        return tenantRepository.findBySlug(slug).map(TenantResponseDTO::from);
    }

    @Transactional
    public TenantResponseDTO create(TenantRequestDTO dto) {
        if (tenantRepository.existsBySlug(dto.getSlug())) {
            throw new IllegalArgumentException("Ya existe un tenant con el slug: " + dto.getSlug());
        }
        Tenant tenant = new Tenant();
        tenant.setSlug(dto.getSlug().toLowerCase().trim());
        tenant.setName(dto.getName().trim());
        tenant.setPlan(dto.getPlan() != null ? dto.getPlan() : "starter");
        tenant.setActive(true);
        return TenantResponseDTO.from(tenantRepository.save(tenant));
    }

    @Transactional
    public Optional<TenantResponseDTO> update(Long id, TenantRequestDTO dto) {
        return tenantRepository.findById(id).map(tenant -> {
            tenant.setName(dto.getName().trim());
            if (dto.getPlan() != null) tenant.setPlan(dto.getPlan());
            return TenantResponseDTO.from(tenantRepository.save(tenant));
        });
    }

    @Transactional
    public Optional<TenantResponseDTO> toggle(Long id) {
        return tenantRepository.findById(id).map(tenant -> {
            tenant.setActive(!Boolean.TRUE.equals(tenant.getActive()));
            return TenantResponseDTO.from(tenantRepository.save(tenant));
        });
    }

    @Transactional
    public boolean delete(Long id) {
        if (!tenantRepository.existsById(id)) return false;
        tenantRepository.deleteById(id);
        return true;
    }
}
