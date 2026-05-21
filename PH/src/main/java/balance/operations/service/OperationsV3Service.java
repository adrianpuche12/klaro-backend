package balance.operations.service;

import balance.model.ClosingDeposit;
import balance.model.GastoAdmin;
import balance.model.SalaryPayment;
import balance.model.SupplierPayment;
import balance.model.Transaction;
import balance.model.Store;
import balance.operations.dto.OperationDTO;
import balance.operations.dto.OperationSummaryDTO;
import balance.repository.*;
import balance.sales.model.Sale;
import balance.sales.repository.SaleRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class OperationsV3Service {

    @Autowired private ClosingDepositRepository closingDepositRepository;
    @Autowired private SaleRepository           saleRepository;
    @Autowired private SupplierPaymentRepository supplierPaymentRepository;
    @Autowired private SalaryPaymentRepository  salaryPaymentRepository;
    @Autowired private GastoAdminRepository     gastoAdminRepository;
    @Autowired private TransactionRepository    transactionRepository;
    @Autowired private StoreRepository          storeRepository;

    /**
     * Lista unificada de operaciones del tenant con filtros opcionales.
     * Combina: CLOSING, SALE, SUPPLIER, SALARY, GASTO_ADMIN, TRANSACTION
     */
    public List<OperationDTO> getOperations(LocalDate from, LocalDate to,
                                             String typeFilter, Long storeId,
                                             String sort, int page, int size) {
        Long tenantId = TenantSecurityUtils.requireTenantId();

        // Validar store si se especifica
        if (storeId != null) {
            storeRepository.findByIdAndTenantId(storeId, tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Local no encontrado"));
        }

        List<OperationDTO> all = new ArrayList<>();
        Set<String> types = parseTypes(typeFilter);

        if (types.contains("CLOSING")) all.addAll(getClosings(tenantId, from, to, storeId));
        if (types.contains("SALE"))    all.addAll(getSales(tenantId, from, to, storeId));
        if (types.contains("SUPPLIER")) all.addAll(getSupplierPayments(tenantId, from, to, storeId));
        if (types.contains("SALARY"))  all.addAll(getSalaryPayments(tenantId, from, to, storeId));
        if (types.contains("GASTO_ADMIN")) all.addAll(getGastosAdmin(tenantId, from, to));
        if (types.contains("TRANSACTION")) all.addAll(getTransactions(tenantId, from, to, storeId));

        // Ordenar
        sortOperations(all, sort);

        // Paginar
        int fromIdx = page * size;
        if (fromIdx >= all.size()) return List.of();
        return all.subList(fromIdx, Math.min(fromIdx + size, all.size()));
    }

    public OperationSummaryDTO getSummary(LocalDate from, LocalDate to, Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();

        List<OperationDTO> all = getOperations(from, to, null, storeId, "DATE_DESC", 0, Integer.MAX_VALUE);

        BigDecimal totalIncome  = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        BigDecimal totalCash    = BigDecimal.ZERO;
        BigDecimal totalCard    = BigDecimal.ZERO;
        Map<String, BigDecimal> byType = new LinkedHashMap<>();

        for (OperationDTO op : all) {
            BigDecimal amt = op.getAmount() != null ? op.getAmount() : BigDecimal.ZERO;
            String type = op.getType();

            if ("SALE".equals(type) || "CLOSING".equals(type)) {
                totalIncome = totalIncome.add(amt);
            } else {
                totalExpense = totalExpense.add(amt);
            }

            if ("CASH".equals(op.getPaymentMethod())) totalCash = totalCash.add(amt);
            if ("CARD".equals(op.getPaymentMethod())) totalCard = totalCard.add(amt);

            byType.merge(type, amt, BigDecimal::add);
        }

        return new OperationSummaryDTO(from, to, all.size(),
                totalIncome, totalExpense, totalCash, totalCard, byType);
    }

    // ── Conversores por tipo ─────────────────────────────────────────────────

    private List<OperationDTO> getClosings(Long tenantId, LocalDate from, LocalDate to, Long storeId) {
        List<ClosingDeposit> list = storeId != null
                ? closingDepositRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, from, to)
                : closingDepositRepository.findByTenantIdAndDateRange(tenantId, from, to);

        return list.stream().map(c -> OperationDTO.of(
                "CLOSING", c.getId(), c.getDepositDate(), c.getAmount(),
                c.getStore() != null ? c.getStore().getId() : null,
                c.getStore() != null ? c.getStore().getName() : null,
                c.getUsername(), "Cierre de caja", null, null, c.getImageUri()
        )).toList();
    }

    private List<OperationDTO> getSales(Long tenantId, LocalDate from, LocalDate to, Long storeId) {
        List<Sale> list;
        // Usar la query strict (sin IS NULL) porque OperationsV3 siempre recibe fechas no-nulas.
        // La variante nullable falla en Hibernate 6 con parámetros LocalDate no-nulos.
        if (storeId != null) {
            list = saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(storeId, tenantId, from, to);
        } else {
            list = storeRepository.findByTenantId(tenantId).stream()
                    .flatMap(s -> saleRepository.findByStoreIdAndTenantIdAndDateRangeStrict(s.getId(), tenantId, from, to).stream())
                    .toList();
        }

        return list.stream().map(s -> OperationDTO.of(
                "SALE", s.getId(), s.getSaleDate(), s.getTotal(),
                s.getStore() != null ? s.getStore().getId() : null,
                s.getStore() != null ? s.getStore().getName() : null,
                s.getUsername(), "Venta", s.getStatus(), s.getPaymentMethod(), null
        )).toList();
    }

    private List<OperationDTO> getSupplierPayments(Long tenantId, LocalDate from, LocalDate to, Long storeId) {
        List<SupplierPayment> list = storeId != null
                ? supplierPaymentRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, from, to)
                : supplierPaymentRepository.findByTenantIdAndDateRange(tenantId, from, to);

        return list.stream().map(s -> OperationDTO.of(
                "SUPPLIER", s.getId(), s.getPaymentDate(), s.getAmount(),
                s.getStore() != null ? s.getStore().getId() : null,
                s.getStore() != null ? s.getStore().getName() : null,
                s.getUsername(), s.getDescription() != null ? s.getDescription() : s.getSupplier(),
                null, null, s.getImageUri()
        )).toList();
    }

    private List<OperationDTO> getSalaryPayments(Long tenantId, LocalDate from, LocalDate to, Long storeId) {
        List<SalaryPayment> list = storeId != null
                ? salaryPaymentRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, from, to)
                : salaryPaymentRepository.findByTenantIdAndDateRange(tenantId, from, to);

        return list.stream().map(s -> OperationDTO.of(
                "SALARY", s.getId(), s.getSalaryDate(), s.getAmount(),
                s.getStore() != null ? s.getStore().getId() : null,
                s.getStore() != null ? s.getStore().getName() : null,
                s.getUsername(), s.getDescription(), null, null, s.getImageUri()
        )).toList();
    }

    private List<OperationDTO> getGastosAdmin(Long tenantId, LocalDate from, LocalDate to) {
        List<GastoAdmin> list = gastoAdminRepository.findByTenantIdAndDateRange(tenantId, from, to);
        return list.stream().map(g -> OperationDTO.of(
                "GASTO_ADMIN", g.getId(), g.getFecha(), g.getMonto(),
                null, null, g.getUsername(), g.getDescripcion(), null, null, null
        )).toList();
    }

    private List<OperationDTO> getTransactions(Long tenantId, LocalDate from, LocalDate to, Long storeId) {
        List<Transaction> list = storeId != null
                ? transactionRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, from, to)
                : transactionRepository.findByTenantIdAndDateRange(tenantId, from, to);

        return list.stream().map(t -> OperationDTO.of(
                "TRANSACTION", t.getId(), t.getDate(), t.getAmount(),
                t.getStore() != null ? t.getStore().getId() : null,
                t.getStore() != null ? t.getStore().getName() : null,
                null, t.getDescription(), null, t.getType(), t.getImageUri()
        )).toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Set<String> parseTypes(String typeFilter) {
        if (typeFilter == null || typeFilter.isBlank()) {
            return Set.of("CLOSING", "SALE", "SUPPLIER", "SALARY", "GASTO_ADMIN", "TRANSACTION");
        }
        return Arrays.stream(typeFilter.split(","))
                .map(String::trim).map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    private void sortOperations(List<OperationDTO> list, String sort) {
        if (sort == null) sort = "DATE_DESC";
        Comparator<OperationDTO> comparator = switch (sort) {
            case "DATE_ASC"    -> Comparator.comparing(OperationDTO::getDate, Comparator.nullsLast(Comparator.naturalOrder()));
            case "AMOUNT_DESC" -> Comparator.comparing(OperationDTO::getAmount, Comparator.nullsLast(Comparator.reverseOrder()));
            case "AMOUNT_ASC"  -> Comparator.comparing(OperationDTO::getAmount, Comparator.nullsLast(Comparator.naturalOrder()));
            default            -> Comparator.comparing(OperationDTO::getDate, Comparator.nullsLast(Comparator.reverseOrder()));
        };
        list.sort(comparator);
    }

    /** Para uso interno del ReportService (sin paginación). */
    public List<OperationDTO> getAllForExport(LocalDate from, LocalDate to, String typeFilter, Long storeId) {
        return getOperations(from, to, typeFilter, storeId, "DATE_DESC", 0, Integer.MAX_VALUE);
    }
}
