package balance.catalog.service;

import balance.catalog.dto.CategoryRequestDTO;
import balance.catalog.dto.CategoryResponseDTO;
import balance.catalog.model.Category;
import balance.catalog.repository.CategoryRepository;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private StoreRepository storeRepository;

    public List<CategoryResponseDTO> getTree(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        List<Category> all = categoryRepository.findAllByStoreId(storeId);

        List<Long> catIds = all.stream().map(Category::getId).collect(Collectors.toList());
        Map<Long, Long> productCounts = new HashMap<>();
        if (!catIds.isEmpty()) {
            categoryRepository.countProductsByCategoryIds(catIds)
                    .forEach(row -> productCounts.put((Long) row[0], (Long) row[1]));
        }

        Map<Long, CategoryResponseDTO> dtoMap = new HashMap<>();
        for (Category c : all) {
            long count = productCounts.getOrDefault(c.getId(), 0L);
            dtoMap.put(c.getId(), CategoryResponseDTO.from(c, count));
        }

        List<CategoryResponseDTO> roots = new ArrayList<>();
        for (Category c : all) {
            CategoryResponseDTO dto = dtoMap.get(c.getId());
            if (c.getParent() == null) {
                roots.add(dto);
            } else {
                CategoryResponseDTO parent = dtoMap.get(c.getParent().getId());
                if (parent != null) parent.getChildren().add(dto);
            }
        }
        return roots;
    }

    private CategoryResponseDTO toDTO(Category category) {
        long productCount = categoryRepository.countProductsByCategoryId(category.getId());
        CategoryResponseDTO dto = CategoryResponseDTO.from(category, productCount);
        List<CategoryResponseDTO> childDTOs = category.getChildren()
                .stream().map(this::toDTO).toList();
        dto.setChildren(childDTOs);
        return dto;
    }

    public Optional<CategoryResponseDTO> findById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return categoryRepository.findById(id)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .map(this::toDTO);
    }

    @Transactional
    public Optional<CategoryResponseDTO> createRoot(Long storeId, CategoryRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        Category category = buildCategory(dto, store, null, tenantId);
        return Optional.of(toDTO(categoryRepository.save(category)));
    }

    @Transactional
    public Optional<CategoryResponseDTO> createChild(Long storeId, Long parentId, CategoryRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        Optional<Category> parent = categoryRepository.findById(parentId)
                .filter(p -> tenantId.equals(p.getTenantId()));
        if (parent.isEmpty()) return Optional.empty();

        Category category = buildCategory(dto, store, parent.get(), tenantId);
        return Optional.of(toDTO(categoryRepository.save(category)));
    }

    @Transactional
    public Optional<CategoryResponseDTO> update(Long id, CategoryRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return categoryRepository.findById(id)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .map(category -> {
                    category.setName(dto.getName().trim());
                    category.setDescription(dto.getDescription());
                    if (dto.getDisplayOrder() != null) category.setDisplayOrder(dto.getDisplayOrder());
                    return toDTO(categoryRepository.save(category));
                });
    }

    @Transactional
    public Optional<CategoryResponseDTO> toggle(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return categoryRepository.findById(id)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .map(category -> {
                    category.setActive(!Boolean.TRUE.equals(category.getActive()));
                    return toDTO(categoryRepository.save(category));
                });
    }

    @Transactional
    public boolean delete(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return categoryRepository.findById(id)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .map(c -> {
                    categoryRepository.deleteById(id);
                    return true;
                }).orElse(false);
    }

    private Category buildCategory(CategoryRequestDTO dto, Store store, Category parent, Long tenantId) {
        Category category = new Category();
        category.setName(dto.getName().trim());
        category.setDescription(dto.getDescription());
        category.setDisplayOrder(dto.getDisplayOrder() != null ? dto.getDisplayOrder() : 0);
        category.setActive(true);
        category.setStore(store);
        category.setParent(parent);
        category.setTenantId(tenantId);
        return category;
    }
}
