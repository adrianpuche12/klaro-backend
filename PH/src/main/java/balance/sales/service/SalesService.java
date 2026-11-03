package balance.sales.service;

import balance.tenant.context.RecursoNoEncontradoException;
import balance.catalog.model.Product;
import balance.catalog.repository.ProductRepository;
import balance.common.exception.ConflictException;
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
import balance.sales.model.ShiftReconciliation;
import balance.sales.model.IdempotencyKey;
import balance.sales.repository.IdempotencyKeyRepository;
import balance.sales.repository.SaleRepository;
import balance.sales.repository.ShiftReconciliationRepository;
import balance.sales.repository.ShiftRepository;
import balance.service.FormsService;
import balance.tax.service.TaxService;
import balance.tenant.context.TenantSecurityUtils;
import balance.tenant.service.TenantConfigService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class SalesService {

    private static final Logger log = LoggerFactory.getLogger(SalesService.class);

    @Autowired private SaleRepository saleRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private InventoryService inventoryService;
    @Autowired private FormsService formsService;
    @Autowired private TenantConfigService tenantConfigService;
    @Autowired private TaxService taxService;
    @Autowired private PermissionGuard permissionGuard;
    @Autowired private ShiftExpenseService shiftExpenseService;
    @Autowired private ShiftReconciliationRepository shiftReconciliationRepository;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private ObjectMapper objectMapper;

    /** Máximo largo aceptado para el header Idempotency-Key (un UUID tiene 36). */
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    // ── Precio de una venta: UN solo cálculo para cotizar y para cobrar ──────────

    /** Ítem con precio calculado. */
    record PricedLine(Product product, int quantity, BigDecimal unitPrice, BigDecimal subtotal, BigDecimal tax) {}

    /** Venta con precio calculado por el backend (ítems + impuestos + recargo + reparto del pago). */
    record PricedSale(List<PricedLine> lines, BigDecimal subtotal, BigDecimal tax,
                      BigDecimal surchargeRate, PaymentSplit payment) {}

    /**
     * Calcula el precio de la venta desde los ítems: precio del catálogo x cantidad, impuestos
     * de la empresa, recargo por tarjeta de tenant_config y reparto del pago. Lo usan
     * {@link #quote} y {@link #createSale}: el total cotizado es el total cobrado.
     * El cliente nunca decide montos: en MIXED solo propone el reparto y tiene que cuadrar.
     */
    private PricedSale price(Long tenantId, Store store, SaleRequestDTO request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("La venta debe tener al menos un producto");
        }
        List<PricedLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;

        for (SaleItemRequestDTO itemReq : request.getItems()) {
            if (itemReq.getProductId() == null) {
                throw new IllegalArgumentException("El producto es obligatorio");
            }
            if (itemReq.getQuantity() == null || itemReq.getQuantity() < 1) {
                throw new IllegalArgumentException("La cantidad debe ser al menos 1");
            }
            Product product = productRepository.findById(itemReq.getProductId())
                    .filter(p -> tenantId.equals(p.getTenantId()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Producto no encontrado: " + itemReq.getProductId()));

            // El producto tiene que ser del local del turno: el catálogo y el stock son por
            // local, y con un producto de otro local se descontaba stock ajeno.
            if (product.getStore() == null || !store.getId().equals(product.getStore().getId())) {
                throw new IllegalArgumentException("El producto no pertenece a este local: " + product.getName());
            }

            if (!Boolean.TRUE.equals(product.getActive())) {
                throw new IllegalArgumentException("Producto inactivo: " + product.getName());
            }

            BigDecimal itemSubtotal = product.getPrice()
                    .multiply(BigDecimal.valueOf(itemReq.getQuantity()))
                    .setScale(PaymentSplit.MONEY_SCALE, RoundingMode.HALF_UP);

            // Impuesto dinámico de la empresa para este producto
            BigDecimal itemTax = taxService.calculateTax(tenantId, product, itemSubtotal);
            if (itemTax == null) itemTax = BigDecimal.ZERO;

            lines.add(new PricedLine(product, itemReq.getQuantity(), product.getPrice(), itemSubtotal, itemTax));
            subtotal = subtotal.add(itemSubtotal);
            totalTax = totalTax.add(itemTax);
        }

        // Recargo por tarjeta (SPRINT-12): configurable por tenant, se aplica solo sobre la
        // porción pagada con tarjeta -- nunca sobre la de efectivo.
        BigDecimal surchargeRate = tenantConfigService.getCardSurchargeRate();
        PaymentSplit payment = PaymentSplit.of(request.getPaymentMethod(), subtotal.add(totalTax), surchargeRate,
                request.getCashAmount(), request.getCardAmount());
        return new PricedSale(lines, subtotal, totalTax, surchargeRate, payment);
    }

    /** Turno del tenant, abierto, y con permiso POS en su local (mismo control para cotizar y vender). */
    private Shift requireOpenShiftForPos(Long tenantId, Long shiftId) {
        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Turno no encontrado"));

        if (ShiftStatus.CLOSED == shift.getStatus()) {
            throw new IllegalStateException("El turno ya está cerrado");
        }
        permissionGuard.assertAccess(PermissionModule.POS, shift.getStore().getId());
        return shift;
    }

    /**
     * Cotiza una venta sin registrar nada (POST /shifts/{id}/sales/quote): mismo cálculo,
     * mismas validaciones y mismos permisos que {@link #createSale}.
     */
    @Transactional(readOnly = true)
    public SaleQuoteDTO quote(Long shiftId, SaleRequestDTO request) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = requireOpenShiftForPos(tenantId, shiftId);
        PricedSale priced = price(tenantId, shift.getStore(), request);
        PaymentSplit p = priced.payment();
        List<SaleQuoteDTO.Line> lines = priced.lines().stream()
                .map(l -> new SaleQuoteDTO.Line(l.product().getId(), l.product().getName(), l.quantity(),
                        l.unitPrice(), l.subtotal(), l.tax()))
                .toList();
        return new SaleQuoteDTO(priced.subtotal(), priced.tax(), priced.subtotal().add(priced.tax()),
                priced.surchargeRate(), p.surcharge(), p.total(), p.paymentMethod(),
                p.cashAmount(), p.cardBaseAmount(), p.cardAmount(), lines);
    }

    @Transactional
    public SaleResponseDTO createSale(Long shiftId, SaleRequestDTO request) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = requireOpenShiftForPos(tenantId, shiftId);
        Store store = shift.getStore();

        PricedSale priced = price(tenantId, store, request);

        Sale sale = new Sale();
        sale.setShift(shift);
        sale.setStore(store);
        sale.setUsername(request.getUsername());
        ZoneId tz = ZoneId.of(tenantConfigService.getTimezone());
        sale.setSaleDate(LocalDate.now(tz));
        sale.setStatus(SaleStatus.OPEN);
        sale.setTenantId(tenantId);

        for (PricedLine line : priced.lines()) {
            SaleItem item = new SaleItem();
            item.setSale(sale);
            item.setProduct(line.product());
            item.setProductNameSnapshot(line.product().getName());
            item.setUnitPriceSnapshot(line.unitPrice());
            item.setQuantity(line.quantity());
            item.setSubtotal(line.subtotal());
            sale.getItems().add(item);
        }

        PaymentSplit payment = priced.payment();
        sale.setSubtotal(priced.subtotal());
        sale.setIsv(priced.tax());   // "isv" almacena el impuesto calculado dinámicamente
        sale.setPaymentMethod(payment.paymentMethod());
        sale.setCashAmount(payment.cashAmount());
        sale.setCardAmount(payment.cardAmount());
        sale.setTotal(payment.total());

        saleRepository.save(sale);
        // Misma transacción: si algún ítem no tiene stock (409), no queda ni la venta ni
        // ningún descuento parcial.
        deductStock(store.getId(), sale.getItems(), request.getUsername());
        return SaleResponseDTO.from(sale);
    }

    /**
     * Registra una venta con Idempotency-Key (header opcional de POST /shifts/{id}/sales).
     *
     * <ul>
     *   <li>Sin clave: igual que {@link #createSale}.</li>
     *   <li>Clave nueva (o vencida, más de 24 h): registra la venta y guarda el JSON exacto de
     *       la respuesta, todo en la misma transacción. Si la venta falla (p. ej. 409 por
     *       stock), la clave tampoco queda registrada y el reintento vuelve a intentar.</li>
     *   <li>Clave vigente con el mismo pedido: no registra nada y devuelve el mismo cuerpo.
     *       Si la primera venta todavía se está procesando, espera a que termine.</li>
     *   <li>Clave vigente con otro pedido: 409.</li>
     * </ul>
     * La clave es por empresa: la misma clave en otra empresa es otra venta (y RLS impide verla).
     */
    @Transactional
    public IdempotentSaleResult createSaleIdempotent(Long shiftId, SaleRequestDTO request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new IdempotentSaleResult(createSale(shiftId, request), false);
        }
        String key = idempotencyKey.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key demasiado larga (máximo "
                    + MAX_IDEMPOTENCY_KEY_LENGTH + " caracteres)");
        }
        Long tenantId = TenantSecurityUtils.requireTenantId();
        String hash = requestHash(shiftId, request);

        if (idempotencyKeyRepository.claim(tenantId, key, hash) == 0) {
            IdempotencyKey existing = idempotencyKeyRepository.findByTenantIdAndIdempotencyKey(tenantId, key)
                    .orElseThrow(() -> new ConflictException(
                            "La venta con esta Idempotency-Key se está procesando; reintentar en unos segundos"));
            if (!hash.equals(existing.getRequestHash())) {
                throw new ConflictException("La Idempotency-Key ya se usó para otra venta");
            }
            if (existing.getResponse() == null) {
                throw new ConflictException(
                        "La venta con esta Idempotency-Key se está procesando; reintentar en unos segundos");
            }
            return new IdempotentSaleResult(existing.getResponse(), true);
        }

        idempotencyKeyRepository.purgeExpired(tenantId);
        SaleResponseDTO dto = createSale(shiftId, request);
        try {
            idempotencyKeyRepository.complete(tenantId, key, dto.getId(), objectMapper.writeValueAsString(dto));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo registrar la respuesta de la venta", e);
        }
        return new IdempotentSaleResult(dto, false);
    }

    /** Huella del pedido (turno, medio de pago, montos e ítems), sin el usuario (lo pone el token). */
    static String requestHash(Long shiftId, SaleRequestDTO request) {
        StringBuilder sb = new StringBuilder()
                .append(shiftId).append('|')
                .append(request.getPaymentMethod() != null ? request.getPaymentMethod() : "CASH").append('|')
                .append(plain(request.getCashAmount())).append('|')
                .append(plain(request.getCardAmount()));
        if (request.getItems() != null) {
            for (SaleItemRequestDTO item : request.getItems()) {
                sb.append('|').append(item.getProductId()).append('x').append(item.getQuantity());
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
    }

    @Transactional
    public void cancelSale(Long saleId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Sale sale = saleRepository.findByIdAndTenantId(saleId, tenantId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Venta no encontrada"));

        if (SaleStatus.CONFIRMED == sale.getStatus()) {
            throw new IllegalStateException("No se puede cancelar una venta ya confirmada");
        }
        revertStock(sale.getStore().getId(), sale.getItems(), "cancel");
        saleRepository.delete(sale);
    }

    public List<SaleResponseDTO> getSalesByShift(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Turno no encontrado"));
        return saleRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(shiftId, tenantId)
                .stream().map(SaleResponseDTO::from).toList();
    }

    public SaleResponseDTO getSaleById(Long saleId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return saleRepository.findByIdAndTenantId(saleId, tenantId)
                .map(SaleResponseDTO::from)
                .orElseThrow(() -> new RecursoNoEncontradoException("Venta no encontrada"));
    }

    public List<SaleResponseDTO> getSalesByStore(Long storeId, LocalDate from, LocalDate to) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return saleRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, from, to)
                .stream().map(SaleResponseDTO::from).toList();
    }

    /**
     * Resumen de ventas de un local. {@code onlyUsername != null}: solo las ventas de ese
     * usuario (alcance "solo mis turnos" del cajero, P7), mismo criterio que los informes.
     */
    public DailySummaryDTO getSummaryByStore(Long storeId, LocalDate from, LocalDate to, String onlyUsername) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        List<Sale> sales = saleRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, from, to);
        if (onlyUsername != null) {
            sales = sales.stream()
                    .filter(s -> s.getUsername() != null && s.getUsername().equalsIgnoreCase(onlyUsername))
                    .toList();
        }
        return buildSummary(sales, store, from != null ? from : tenantConfigService.today());
    }

    public DailySummaryDTO getDailySummary(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Turno no encontrado"));
        List<Sale> sales = saleRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(shiftId, tenantId);
        return buildSummary(sales, shift.getStore(), tenantConfigService.today());
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
    public DailyClosingResponseDTO closeShift(Long shiftId, String username, String notes,
                                               BigDecimal openingCash, BigDecimal declaredCash) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = shiftRepository.findForUpdate(shiftId, tenantId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Turno no encontrado"));

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
        deposit.setDepositDate(tenantConfigService.today());
        deposit.setPeriodStart(tenantConfigService.today());
        deposit.setPeriodEnd(tenantConfigService.today());
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
        if (notes != null && !notes.isBlank()) shift.setNotes(notes.trim());
        shiftRepository.save(shift);

        // Reconciliación de caja (SPRINT-12) -- opcional: solo si se declaran
        // ambos montos. Sin esto, cerrar un turno sigue funcionando igual que
        // antes (compatibilidad con el flujo existente).
        ShiftReconciliationResponseDTO reconciliation = null;
        if (openingCash != null && declaredCash != null) {
            BigDecimal totalCashSales = openSales.stream()
                    .map(Sale::getCashAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalExpenses = shiftExpenseService.getTotalExpenses(shiftId, tenantId);
            BigDecimal expectedCash = openingCash.add(totalCashSales).subtract(totalExpenses);

            ShiftReconciliation reconciliationEntity = new ShiftReconciliation();
            reconciliationEntity.setTenantId(tenantId);
            reconciliationEntity.setShift(shift);
            reconciliationEntity.setOpeningCash(openingCash);
            reconciliationEntity.setDeclaredCash(declaredCash);
            reconciliationEntity.setExpectedCash(expectedCash);
            reconciliationEntity.setDifference(declaredCash.subtract(expectedCash));
            reconciliation = ShiftReconciliationResponseDTO.from(
                    shiftReconciliationRepository.save(reconciliationEntity));
        }

        return new DailyClosingResponseDTO(
                shift.getId(), shift.getCode(), tenantConfigService.today(),
                shift.getStore().getId(), shift.getStore().getName(),
                openSales.size(), totalAmount, saved.getId(), reconciliation);
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

    /** No usa adjustSilent a propósito: si no hay stock suficiente, la venta
     * entera debe abortar (rollback de @Transactional) en vez de cobrarse con
     * el inventario sin descontar -- encontrado en testing E2E vendiendo más
     * cantidad de la disponible: la venta se registraba completa y el stock
     * quedaba sin tocar, sin ningún error visible. */
    private void deductStock(Long storeId, List<SaleItem> items, String username) {
        for (SaleItem item : items) {
            if (item.getProduct() == null) continue;
            StockAdjustmentDTO adj = new StockAdjustmentDTO();
            adj.setProductId(item.getProduct().getId());
            adj.setType("SALIDA");
            adj.setQuantity(item.getQuantity());
            adj.setReason("Venta");
            adj.setUsername(username);
            adj.setSource("SALE");
            inventoryService.adjust(storeId, adj);
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
