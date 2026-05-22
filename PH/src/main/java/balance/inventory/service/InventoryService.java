package balance.inventory.service;

import balance.catalog.model.Product;
import balance.catalog.repository.CategoryRepository;
import balance.catalog.repository.ProductRepository;
import balance.inventory.dto.*;
import balance.inventory.model.InventoryMovement;
import balance.inventory.model.InventoryStock;
import balance.inventory.repository.InventoryMovementRepository;
import balance.inventory.repository.InventoryStockRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    @Autowired private InventoryStockRepository stockRepository;
    @Autowired private InventoryMovementRepository movementRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private CategoryRepository categoryRepository;

    @Transactional
    public List<StockItemDTO> getStock(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        List<Product> products = productRepository.findByStoreIdOrderByNameAsc(storeId);

        return products.stream().map(product -> {
            InventoryStock stock = stockRepository
                    .findByProductIdAndStoreIdAndTenantId(product.getId(), storeId, tenantId)
                    .orElseGet(() -> {
                        InventoryStock s = new InventoryStock();
                        s.setProduct(product);
                        s.setStore(store);
                        s.setTenantId(tenantId);
                        s.setQuantity(0);
                        return stockRepository.save(s);
                    });
            return StockItemDTO.from(stock);
        }).toList();
    }

    public List<StockItemDTO> getLowStock(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return stockRepository.findLowStockByStoreIdAndTenantId(storeId, tenantId)
                .stream().map(StockItemDTO::from).toList();
    }

    public StockSummaryDTO getSummary(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        List<InventoryStock> stocks = stockRepository
                .findByStoreIdAndTenantIdOrderByProductNameAsc(storeId, tenantId);

        long total    = stocks.size();
        long active   = stocks.stream().filter(s -> Boolean.TRUE.equals(s.getProduct().getActive())).count();
        long lowStock = stockRepository.countLowStockByStoreIdAndTenantId(storeId, tenantId);
        long cats     = categoryRepository.findRootsByStoreId(storeId).size();

        BigDecimal value = stocks.stream()
                .map(s -> s.getProduct().getPrice()
                        .multiply(BigDecimal.valueOf(s.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new StockSummaryDTO(total, active, lowStock, cats, value);
    }

    @Transactional
    public StockItemDTO adjust(Long storeId, StockAdjustmentDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        Product product = productRepository.findById(dto.getProductId())
                .filter(p -> tenantId.equals(p.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));

        InventoryStock stock = stockRepository
                .findByProductIdAndStoreIdAndTenantId(dto.getProductId(), storeId, tenantId)
                .orElseGet(() -> {
                    InventoryStock s = new InventoryStock();
                    s.setProduct(product);
                    s.setStore(store);
                    s.setTenantId(tenantId);
                    s.setQuantity(0);
                    return s;
                });

        int delta = "SALIDA".equals(dto.getType()) ? -dto.getQuantity() : dto.getQuantity();
        int newQty = stock.getQuantity() + delta;
        if (newQty < 0) throw new IllegalArgumentException("Stock insuficiente para realizar la salida");
        stock.setQuantity(newQty);
        stockRepository.save(stock);

        InventoryMovement movement = new InventoryMovement();
        movement.setType(dto.getType());
        movement.setQuantity(dto.getQuantity());
        movement.setReason(dto.getReason());
        movement.setNotes(dto.getNotes());
        movement.setUsername(dto.getUsername());
        movement.setPerformedBy(dto.getUsername() != null ? dto.getUsername() : currentUsername());
        movement.setSource(dto.getSource() != null ? dto.getSource() : "MANUAL");
        movement.setProduct(product);
        movement.setStore(store);
        movement.setTenantId(tenantId);
        movementRepository.save(movement);

        return StockItemDTO.from(stock);
    }

    @Transactional
    public void adjustSilent(Long storeId, StockAdjustmentDTO dto) {
        try {
            adjust(storeId, dto);
        } catch (Exception ex) {
            log.warn("adjustSilent: no se pudo ajustar stock [producto={}, tipo={}, qty={}, local={}]: {}",
                    dto.getProductId(), dto.getType(), dto.getQuantity(), storeId, ex.getMessage());
        }
    }

    public List<MovementDTO> getMovements(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return movementRepository.findByStoreIdAndTenantIdOrderByCreatedAtDesc(storeId, tenantId)
                .stream().map(MovementDTO::from).toList();
    }

    /** Retorna el username del usuario autenticado actual, o "system" si no hay sesión. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "system";
    }

    @Transactional
    public void initStock(Product product, Store store) {
        Long tenantId = product.getTenantId();
        if (stockRepository.findByProductIdAndStoreIdAndTenantId(
                product.getId(), store.getId(), tenantId).isEmpty()) {
            InventoryStock stock = new InventoryStock();
            stock.setProduct(product);
            stock.setStore(store);
            stock.setTenantId(tenantId);
            stock.setQuantity(0);
            stockRepository.save(stock);
        }
    }
}
