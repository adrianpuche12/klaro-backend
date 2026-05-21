package balance.catalog.service;

import balance.catalog.dto.StoreRequestDTO;
import balance.catalog.dto.StoreResponseDTO;
import balance.catalog.repository.CategoryRepository;
import balance.catalog.repository.ProductRepository;
import balance.inventory.repository.InventoryMovementRepository;
import balance.inventory.repository.InventoryStockRepository;
import balance.model.Store;
import balance.repository.ClosingDepositRepository;
import balance.repository.SalaryPaymentRepository;
import balance.repository.StoreRepository;
import balance.repository.SupplierPaymentRepository;
import balance.repository.TransactionRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class StoreV2Service {

    @Autowired private StoreRepository storeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private InventoryStockRepository inventoryStockRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ClosingDepositRepository closingDepositRepository;
    @Autowired private SupplierPaymentRepository supplierPaymentRepository;
    @Autowired private SalaryPaymentRepository salaryPaymentRepository;

    public List<StoreResponseDTO> findAll() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return storeRepository.findByTenantId(tenantId)
                .stream().map(StoreResponseDTO::from).toList();
    }

    public List<StoreResponseDTO> findAllActive() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return storeRepository.findByTenantIdAndActive(tenantId, true)
                .stream().map(StoreResponseDTO::from).toList();
    }

    public Optional<StoreResponseDTO> findById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return storeRepository.findByIdAndTenantId(id, tenantId)
                .map(StoreResponseDTO::from);
    }

    @Transactional
    public StoreResponseDTO create(StoreRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = new Store();
        store.setName(dto.getName().trim());
        store.setAddress(dto.getAddress());
        store.setPhone(dto.getPhone());
        store.setActive(true);
        store.setTenantId(tenantId);
        return StoreResponseDTO.from(storeRepository.save(store));
    }

    @Transactional
    public Optional<StoreResponseDTO> update(Long id, StoreRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return storeRepository.findByIdAndTenantId(id, tenantId).map(store -> {
            store.setName(dto.getName().trim());
            store.setAddress(dto.getAddress());
            store.setPhone(dto.getPhone());
            return StoreResponseDTO.from(storeRepository.save(store));
        });
    }

    @Transactional
    public Optional<StoreResponseDTO> toggle(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return storeRepository.findByIdAndTenantId(id, tenantId).map(store -> {
            store.setActive(!Boolean.TRUE.equals(store.getActive()));
            return StoreResponseDTO.from(storeRepository.save(store));
        });
    }

    @Transactional
    public boolean delete(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        if (!storeRepository.existsByIdAndTenantId(id, tenantId)) return false;

        boolean hasHistory =
            !transactionRepository.findByStoreIdAndTenantIdOrderByDateDesc(id, tenantId).isEmpty() ||
            !closingDepositRepository.findByStoreIdAndTenantIdOrderByDepositDateDesc(id, tenantId).isEmpty() ||
            !supplierPaymentRepository.findByStoreIdAndTenantIdOrderByPaymentDateDesc(id, tenantId).isEmpty() ||
            !salaryPaymentRepository.findByStoreIdAndTenantIdOrderBySalaryDateDesc(id, tenantId).isEmpty();

        if (hasHistory) {
            throw new IllegalStateException("No se puede eliminar un local con historial de operaciones. Desactivalo en su lugar.");
        }

        var products = productRepository.findByStoreIdOrderByNameAsc(id);
        for (var product : products) {
            inventoryMovementRepository.deleteByProductId(product.getId());
            inventoryStockRepository.deleteByProductId(product.getId());
        }
        productRepository.deleteAll(products);

        var rootCategories = categoryRepository.findRootsByStoreId(id);
        categoryRepository.deleteAll(rootCategories);

        storeRepository.deleteById(id);
        return true;
    }
}
