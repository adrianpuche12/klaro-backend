package balance.dashboard.service;

import balance.catalog.repository.ProductRepository;
import balance.dashboard.dto.DashboardDTO;
import balance.dashboard.dto.StoreDashboardDTO;
import balance.inventory.repository.InventoryStockRepository;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.sales.model.Sale;
import balance.sales.model.Shift;
import balance.sales.repository.SaleRepository;
import balance.sales.repository.ShiftRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
public class DashboardService {

    private static final ZoneId HONDURAS_TZ = ZoneId.of("America/Tegucigalpa");

    @Autowired private StoreRepository          storeRepository;
    @Autowired private ShiftRepository          shiftRepository;
    @Autowired private SaleRepository           saleRepository;
    @Autowired private InventoryStockRepository stockRepository;
    @Autowired private ProductRepository        productRepository;

    @Transactional(readOnly = true)
    public DashboardDTO getDashboard() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        LocalDate today = LocalDate.now(HONDURAS_TZ);

        List<Store> activeStores = storeRepository.findByTenantIdAndActive(tenantId, true);

        List<StoreDashboardDTO> storeDTOs = activeStores.stream()
                .map(store -> buildStoreDTO(store, tenantId, today))
                .toList();

        // Usar strict (fechas no-nulas) para evitar bug Hibernate 6 con IS NULL en LocalDate
        long totalSalesToday = activeStores.stream()
                .mapToLong(s -> saleRepository
                        .findByStoreIdAndTenantIdAndDateRangeStrict(s.getId(), tenantId, today, today).size())
                .sum();

        BigDecimal totalAmountToday = activeStores.stream()
                .flatMap(s -> saleRepository
                        .findByStoreIdAndTenantIdAndDateRangeStrict(s.getId(), tenantId, today, today).stream())
                .map(Sale::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DashboardDTO(storeDTOs, totalSalesToday, totalAmountToday);
    }

    private StoreDashboardDTO buildStoreDTO(Store store, Long tenantId, LocalDate today) {
        StoreDashboardDTO dto = new StoreDashboardDTO();
        dto.setStoreId(store.getId());
        dto.setStoreName(store.getName());

        Optional<Shift> activeShift = shiftRepository
                .findByStoreIdAndStatusAndTenantId(store.getId(), "OPEN", tenantId);

        if (activeShift.isPresent()) {
            Shift shift = activeShift.get();
            dto.setHasActiveShift(true);
            dto.setShiftCode(shift.getCode());
            dto.setShiftUsername(shift.getUsername());
            dto.setShiftOpenedAt(shift.getOpenedAt());

            List<Sale> sales = saleRepository
                    .findOpenByShiftIdAndTenantId(shift.getId(), tenantId);
            dto.setShiftSalesCount(sales.size());
            BigDecimal total = sales.stream()
                    .map(Sale::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            dto.setShiftSalesTotal(total);
        } else {
            dto.setHasActiveShift(false);
            dto.setShiftSalesTotal(BigDecimal.ZERO);
        }

        long lowStock  = stockRepository.countLowStockByStoreIdAndTenantId(store.getId(), tenantId);
        long totalProd = productRepository.findByStoreIdOrderByNameAsc(store.getId()).size();
        BigDecimal estimatedValue = stockRepository
                .findByStoreIdAndTenantIdOrderByProductNameAsc(store.getId(), tenantId)
                .stream()
                .map(s -> s.getProduct().getPrice().multiply(BigDecimal.valueOf(s.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        dto.setLowStockCount(lowStock);
        dto.setTotalProducts(totalProd);
        dto.setEstimatedValue(estimatedValue);

        return dto;
    }
}
