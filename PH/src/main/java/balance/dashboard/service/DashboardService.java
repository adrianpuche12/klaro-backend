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
import balance.common.enums.ShiftStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

        // Batch: 1 query para todas las ventas de hoy (reemplaza 2N queries por local)
        List<Sale> todaySales = saleRepository.findByTenantIdAndDateRangeStrict(tenantId, today, today);

        // Batch: 1 query para todos los turnos abiertos (reemplaza N queries por local)
        List<Shift> openShifts = shiftRepository.findByTenantIdAndStatus(tenantId, ShiftStatus.OPEN);
        Map<Long, Shift> shiftByStore = openShifts.stream()
                .collect(Collectors.toMap(sh -> sh.getStore().getId(), sh -> sh));

        // Batch: 1 query para ventas abiertas de todos los turnos (reemplaza N queries por turno)
        List<Long> shiftIds = openShifts.stream().map(Shift::getId).toList();
        Map<Long, List<Sale>> openSalesByShift = shiftIds.isEmpty()
                ? Collections.emptyMap()
                : saleRepository.findOpenByShiftIdsAndTenantId(shiftIds, tenantId).stream()
                        .collect(Collectors.groupingBy(s -> s.getShift().getId()));

        List<StoreDashboardDTO> storeDTOs = activeStores.stream()
                .map(store -> buildStoreDTO(store, tenantId, shiftByStore, openSalesByShift))
                .toList();

        long totalSalesToday = todaySales.size();
        BigDecimal totalAmountToday = todaySales.stream()
                .map(Sale::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DashboardDTO(storeDTOs, totalSalesToday, totalAmountToday);
    }

    private StoreDashboardDTO buildStoreDTO(Store store, Long tenantId,
            Map<Long, Shift> shiftByStore, Map<Long, List<Sale>> openSalesByShift) {
        StoreDashboardDTO dto = new StoreDashboardDTO();
        dto.setStoreId(store.getId());
        dto.setStoreName(store.getName());

        Shift shift = shiftByStore.get(store.getId());
        if (shift != null) {
            dto.setHasActiveShift(true);
            dto.setShiftCode(shift.getCode());
            dto.setShiftUsername(shift.getUsername());
            dto.setShiftOpenedAt(shift.getOpenedAt());

            List<Sale> sales = openSalesByShift.getOrDefault(shift.getId(), List.of());
            dto.setShiftSalesCount(sales.size());
            BigDecimal total = sales.stream().map(Sale::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
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
