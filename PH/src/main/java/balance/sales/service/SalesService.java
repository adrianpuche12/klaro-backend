package balance.sales.service;

import balance.catalog.model.Product;
import balance.catalog.repository.ProductRepository;
import balance.common.enums.SaleStatus;
import balance.common.enums.ShiftStatus;
import balance.inventory.dto.StockAdjustmentDTO;
import balance.inventory.service.InventoryService;
import balance.model.ClosingDeposit;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.sales.dto.*;
import balance.sales.model.Sale;
import balance.sales.model.SaleItem;
import balance.sales.model.Shift;
import balance.sales.repository.SaleRepository;
import balance.sales.repository.ShiftRepository;
import balance.service.FormsService;
import balance.tax.service.TaxService;
import balance.tenant.context.TenantSecurityUtils;
import balance.tenant.service.TenantConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class SalesService {

    private static final Logger log = LoggerFactory.getLogger(SalesService.class);
    private static final ZoneId HONDURAS_TZ = ZoneId.of("America/Tegucigalpa");
    private static final BigDecimal ISV_RATE = BigDecimal.ZERO;

    @Autowired private SaleRepository saleRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private InventoryService inventoryService;
    @Autowired private FormsService formsService;
    @Autowired private TenantConfigService tenantConfigService;
    @Autowired private TaxService taxService;

    @Transactional
    public SaleResponseDTO createSale(Long shiftId, SaleRequestDTO request) {
        Long tenantId = TenantSecurityUtils.requireTenantId();

        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Turno no encontrado"));

        if (ShiftStatus.CLOSED == shift.getStatus()) {
            throw new IllegalStateException("El turno ya está cerrado");
        }

        Store store = shift.getStore();

        Sale sale = new Sale();
        sale.setShift(shift);
        sale.setStore(store);
        sale.setUsername(request.getUsername());
        ZoneId tz = ZoneId.of(tenantConfigService.getTimezone());
        sale.setSaleDate(LocalDate.now(tz));
        sale.setStatus(SaleStatus.OPEN);
        sale.setTenantId(tenantId);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;

        for (SaleItemRequestDTO itemReq : request.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .filter(p -> tenantId.equals(p.getTenantId()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Producto no encontrado: " + itemReq.getProductId()));

            if (!Boolean.TRUE.equals(product.getActive())) {
                throw new IllegalArgumentException("Producto inactivo: " + product.getName());
            }

            BigDecimal itemSubtotal = product.getPrice()
                    .multiply(BigDecimal.valueOf(itemReq.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);

            // Calcular impuesto dinámico del tenant para este producto
            BigDecimal itemTax = taxService.calculateTax(tenantId, product, itemSubtotal);

            SaleItem item = new SaleItem();
            item.setSale(sale);
            item.setProduct(product);
            item.setProductNameSnapshot(product.getName());
            item.setUnitPriceSnapshot(product.getPrice());
            item.setQuantity(itemReq.getQuantity());
            item.setSubtotal(itemSubtotal);

            sale.getItems().add(item);
            subtotal = subtotal.add(itemSubtotal);
            totalTax = totalTax.add(itemTax);
        }

        BigDecimal total = subtotal.add(totalTax);
        sale.setSubtotal(subtotal);
        sale.setIsv(totalTax);   // "isv" almacena el impuesto calculado dinámicamente
        sale.setTotal(total);

        String paymentMethod = request.getPaymentMethod() != null ? request.getPaymentMethod() : "CASH";
        sale.setPaymentMethod(paymentMethod);
        switch (paymentMethod) {
            case "CARD":
                sale.setCashAmount(BigDecimal.ZERO);
                sale.setCardAmount(total);
                break;
            case "MIXED":
                BigDecimal cash = request.getCashAmount() != null ? request.getCashAmount() : BigDecimal.ZERO;
                BigDecimal card = request.getCardAmount() != null ? request.getCardAmount() : BigDecimal.ZERO;
                sale.setCashAmount(cash);
                sale.setCardAmount(card);
                break;
            default:
                sale.setCashAmount(total);
                sale.setCardAmount(BigDecimal.ZERO);
                break;
        }

        saleRepository.save(sale);
        deductStock(store.getId(), sale.getItems(), request.getUsername());
        return SaleResponseDTO.from(sale);
    }

    @Transactional
    public void cancelSale(Long saleId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Sale sale = saleRepository.findByIdAndTenantId(saleId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Venta no encontrada"));

        if (SaleStatus.CONFIRMED == sale.getStatus()) {
            throw new IllegalStateException("No se puede cancelar una venta ya confirmada");
        }
        revertStock(sale.getStore().getId(), sale.getItems(), "cancel");
        saleRepository.delete(sale);
    }

    public List<SaleResponseDTO> getSalesByShift(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Turno no encontrado"));
        return saleRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(shiftId, tenantId)
                .stream().map(SaleResponseDTO::from).toList();
    }

    public SaleResponseDTO getSaleById(Long saleId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return saleRepository.findByIdAndTenantId(saleId, tenantId)
                .map(SaleResponseDTO::from)
                .orElseThrow(() -> new IllegalArgumentException("Venta no encontrada"));
    }

    public List<SaleResponseDTO> getSalesByStore(Long storeId, LocalDate from, LocalDate to) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return saleRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, from, to)
                .stream().map(SaleResponseDTO::from).toList();
    }

    public DailySummaryDTO getSummaryByStore(Long storeId, LocalDate from, LocalDate to) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        List<Sale> sales = saleRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, from, to);
        return buildSummary(sales, store, from != null ? from : LocalDate.now(HONDURAS_TZ));
    }

    public DailySummaryDTO getDailySummary(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Turno no encontrado"));
        List<Sale> sales = saleRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(shiftId, tenantId);
        return buildSummary(sales, shift.getStore(), LocalDate.now(HONDURAS_TZ));
    }

    public Map<String, Object> getCashSummary(Long storeId, LocalDate from, LocalDate to) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        List<Sale> sales = saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(storeId, tenantId, from, to);
        BigDecimal totalCash  = sales.stream().map(Sale::getCashAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCard  = sales.stream().map(Sale::getCardAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSales = sales.stream().map(Sale::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("storeId", storeId, "from", from.toString(), "to", to.toString(),
                "totalSales", totalSales, "totalCash", totalCash,
                "totalCard", totalCard, "saleCount", sales.size());
    }

    @Transactional
    public DailyClosingResponseDTO closeShift(Long shiftId, String username) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Turno no encontrado"));

        if (ShiftStatus.CLOSED == shift.getStatus()) {
            throw new IllegalStateException("El turno ya está cerrado");
        }

        List<Sale> openSales = saleRepository.findOpenByShiftIdAndTenantId(shiftId, tenantId);
        if (openSales.isEmpty()) {
            throw new IllegalStateException("No hay ventas abiertas para cerrar en este turno");
        }

        BigDecimal totalAmount = openSales.stream()
                .map(Sale::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        ClosingDeposit deposit = new ClosingDeposit();
        deposit.setAmount(totalAmount);
        deposit.setClosingsCount(openSales.size());
        deposit.setDepositDate(LocalDate.now(HONDURAS_TZ));
        deposit.setPeriodStart(LocalDate.now(HONDURAS_TZ));
        deposit.setPeriodEnd(LocalDate.now(HONDURAS_TZ));
        deposit.setUsername(username);
        deposit.setStore(shift.getStore());
        deposit.setTenantId(tenantId);
        ClosingDeposit saved = formsService.saveClosingDeposit(deposit);

        openSales.forEach(sale -> {
            sale.setStatus(SaleStatus.CONFIRMED);
            saleRepository.save(sale);
        });

        shift.setStatus(ShiftStatus.CLOSED);
        shift.setClosedAt(java.time.LocalDateTime.now());
        shiftRepository.save(shift);

        return new DailyClosingResponseDTO(
                shift.getId(), shift.getCode(), LocalDate.now(HONDURAS_TZ),
                shift.getStore().getId(), shift.getStore().getName(),
                openSales.size(), totalAmount, saved.getId());
    }

    private DailySummaryDTO buildSummary(List<Sale> sales, Store store, LocalDate date) {
        BigDecimal totalSubtotal = BigDecimal.ZERO;
        BigDecimal totalIsv      = BigDecimal.ZERO;
        BigDecimal totalAmount   = BigDecimal.ZERO;
        BigDecimal totalCash     = BigDecimal.ZERO;
        BigDecimal totalCard     = BigDecimal.ZERO;

        Map<String, int[]>      productQty = new LinkedHashMap<>();
        Map<String, BigDecimal> productSub = new LinkedHashMap<>();
        Map<String, Long>       productIds = new LinkedHashMap<>();

        for (Sale sale : sales) {
            totalSubtotal = totalSubtotal.add(sale.getSubtotal());
            totalIsv      = totalIsv.add(sale.getIsv());
            totalAmount   = totalAmount.add(sale.getTotal());
            totalCash     = totalCash.add(sale.getCashAmount());
            totalCard     = totalCard.add(sale.getCardAmount());
            for (SaleItem item : sale.getItems()) {
                String key = item.getProductNameSnapshot();
                productQty.merge(key, new int[]{item.getQuantity()}, (a, b) -> new int[]{a[0] + b[0]});
                productSub.merge(key, item.getSubtotal(), BigDecimal::add);
                if (item.getProduct() != null) productIds.putIfAbsent(key, item.getProduct().getId());
            }
        }

        List<DailySummaryDTO.ProductSummaryItem> summary = productQty.entrySet().stream()
                .map(e -> new DailySummaryDTO.ProductSummaryItem(
                        productIds.get(e.getKey()), e.getKey(),
                        e.getValue()[0], productSub.get(e.getKey())))
                .sorted(Comparator.comparing(DailySummaryDTO.ProductSummaryItem::getSubtotal).reversed())
                .toList();

        return new DailySummaryDTO(date, store.getId(), store.getName(),
                sales.size(), totalSubtotal, totalIsv, totalAmount, totalCash, totalCard, summary);
    }

    private void deductStock(Long storeId, List<SaleItem> items, String username) {
        for (SaleItem item : items) {
            if (item.getProduct() == null) continue;
            try {
                StockAdjustmentDTO adj = new StockAdjustmentDTO();
                adj.setProductId(item.getProduct().getId());
                adj.setType("SALIDA");
                adj.setQuantity(item.getQuantity());
                adj.setReason("Venta");
                adj.setUsername(username);
                adj.setSource("SALE");
                inventoryService.adjustSilent(storeId, adj);
            } catch (Exception ex) {
                log.warn("No se pudo descontar stock del producto {} en local {}: {}",
                        item.getProduct().getId(), storeId, ex.getMessage());
            }
        }
    }

    private void revertStock(Long storeId, List<SaleItem> items, String username) {
        for (SaleItem item : items) {
            if (item.getProduct() == null) continue;
            try {
                StockAdjustmentDTO adj = new StockAdjustmentDTO();
                adj.setProductId(item.getProduct().getId());
                adj.setType("ENTRADA");
                adj.setQuantity(item.getQuantity());
                adj.setReason("Cancelación de venta");
                adj.setUsername(username);
                adj.setSource("CANCEL");
                inventoryService.adjustSilent(storeId, adj);
            } catch (Exception ex) {
                log.warn("No se pudo revertir stock del producto {} en local {}: {}",
                        item.getProduct().getId(), storeId, ex.getMessage());
            }
        }
    }
}
