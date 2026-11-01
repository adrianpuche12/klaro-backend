package balance.catalog.service;

import balance.catalog.dto.ProductRequestDTO;
import balance.catalog.dto.ProductResponseDTO;
import balance.catalog.model.Product;
import balance.catalog.repository.CategoryRepository;
import balance.catalog.repository.ProductRepository;
import balance.inventory.repository.InventoryMovementRepository;
import balance.inventory.repository.InventoryStockRepository;
import balance.inventory.service.InventoryService;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    @Autowired private ProductRepository productRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Lazy @Autowired private InventoryService inventoryService;
    @Autowired private InventoryStockRepository inventoryStockRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;

    public List<ProductResponseDTO> findByStore(Long storeId, Boolean active, Long categoryId, String search) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        List<Product> products;
        if (search != null && !search.isBlank()) {
            products = productRepository.searchByStoreId(storeId, search.trim());
        } else if (categoryId != null) {
            products = productRepository.findByStoreIdAndCategoryIdOrderByNameAsc(storeId, categoryId);
        } else if (active != null) {
            products = productRepository.findByStoreIdAndActiveOrderByNameAsc(storeId, active);
        } else {
            products = productRepository.findByStoreIdOrderByNameAsc(storeId);
        }
        return products.stream().map(ProductResponseDTO::from).toList();
    }

    /** Local del producto dentro del tenant actual (para chequear permisos antes de operar). */
    public Optional<Long> storeIdOf(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return productRepository.findById(id)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .map(p -> p.getStore().getId());
    }

    public Optional<ProductResponseDTO> findById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return productRepository.findById(id)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .map(ProductResponseDTO::from);
    }

    @Transactional
    public Optional<ProductResponseDTO> create(Long storeId, ProductRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        if (dto.getSku() != null && !dto.getSku().isBlank()
                && productRepository.existsBySkuAndStoreId(dto.getSku().trim(), storeId)) {
            throw new IllegalArgumentException("Ya existe un producto con ese SKU en este local");
        }

        Product product = buildProduct(dto, store, tenantId);
        Product saved = productRepository.save(product);
        inventoryService.initStock(saved, store);
        return Optional.of(ProductResponseDTO.from(saved));
    }

    @Transactional
    public Optional<ProductResponseDTO> update(Long id, ProductRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return productRepository.findById(id)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .map(product -> {
                    if (dto.getSku() != null && !dto.getSku().isBlank()
                            && productRepository.existsBySkuAndStoreIdAndIdNot(
                                dto.getSku().trim(), product.getStore().getId(), id)) {
                        throw new IllegalArgumentException("Ya existe un producto con ese SKU en este local");
                    }
                    applyDTO(product, dto);
                    return ProductResponseDTO.from(productRepository.save(product));
                });
    }

    @Transactional
    public Optional<ProductResponseDTO> toggle(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return productRepository.findById(id)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .map(product -> {
                    product.setActive(!Boolean.TRUE.equals(product.getActive()));
                    return ProductResponseDTO.from(productRepository.save(product));
                });
    }

    @Transactional
    public boolean delete(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return productRepository.findById(id)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .map(product -> {
                    inventoryMovementRepository.deleteByProductId(id);
                    inventoryStockRepository.deleteByProductId(id);
                    productRepository.deleteById(id);
                    return true;
                }).orElse(false);
    }

    private Product buildProduct(ProductRequestDTO dto, Store store, Long tenantId) {
        Product product = new Product();
        product.setStore(store);
        product.setTenantId(tenantId);
        applyDTO(product, dto);
        product.setActive(true);
        return product;
    }

    private void applyDTO(Product product, ProductRequestDTO dto) {
        product.setName(dto.getName().trim());
        product.setSku(dto.getSku() != null ? dto.getSku().trim() : null);
        product.setType(dto.getType() != null ? dto.getType() : "SIMPLE");
        product.setPrice(dto.getPrice());
        product.setMinStock(dto.getMinStock() != null ? dto.getMinStock() : 0);
        // null = no se envió → se conserva; "" = el usuario la borró (antes un PUT sin el
        // campo dejaba la descripción en null).
        if (dto.getDescription() != null) {
            product.setDescription(dto.getDescription().isBlank() ? null : dto.getDescription().trim());
        }

        if (dto.getCategoryId() != null) {
            // La categoría tiene que ser de la misma empresa y del mismo local que el producto
            // (antes se buscaba por id sin filtrar por tenant).
            Long storeId = product.getStore() != null ? product.getStore().getId() : null;
            product.setCategory(categoryRepository.findById(dto.getCategoryId())
                    .filter(c -> product.getTenantId() == null || product.getTenantId().equals(c.getTenantId()))
                    .filter(c -> storeId == null || (c.getStore() != null && storeId.equals(c.getStore().getId())))
                    .orElseThrow(() -> new IllegalArgumentException("La categoría no existe en este local")));
        } else {
            product.setCategory(null);
        }
    }
}
