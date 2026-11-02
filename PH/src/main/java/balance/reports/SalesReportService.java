package balance.reports;

import balance.model.Store;
import balance.repository.StoreRepository;
import balance.reports.ReportTable.ColType;
import balance.reports.ReportTable.Column;
import balance.sales.dto.SaleItemDTO;
import balance.sales.dto.SaleResponseDTO;
import balance.sales.service.SalesService;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Informes de ventas del POS: detalle de ventas y productos vendidos.
 *
 * Los permisos los resuelve el controller (módulo SALES_HISTORY + alcance del usuario);
 * acá llega {@code onlyUsername} cuando el usuario solo puede ver SUS ventas (cajero).
 */
@Service
public class SalesReportService {

    @Autowired private SalesService salesService;
    @Autowired private StoreRepository storeRepository;
    @Autowired private ReportContextFactory contextFactory;
    @Autowired private ReportRenderer renderer;

    public byte[] salesDetail(Long storeId, LocalDate from, LocalDate to, String onlyUsername,
                              ReportFormat format) throws IOException {
        ReportContext ctx = contextFactory.current();
        return renderer.render(buildSalesDetail(load(storeId, from, to, onlyUsername), storeId, from, to, ctx), ctx, format);
    }

    public byte[] productsSold(Long storeId, LocalDate from, LocalDate to, String onlyUsername,
                               ReportFormat format) throws IOException {
        ReportContext ctx = contextFactory.current();
        return renderer.render(buildProductsSold(load(storeId, from, to, onlyUsername), storeId, from, to, ctx), ctx, format);
    }

    /** Ventas de un local (o de todos los locales de la empresa si storeId es null). */
    List<SaleResponseDTO> load(Long storeId, LocalDate from, LocalDate to, String onlyUsername) {
        List<SaleResponseDTO> sales = new ArrayList<>();
        if (storeId != null) {
            sales.addAll(salesService.getSalesByStore(storeId, from, to));
        } else {
            Long tenantId = TenantSecurityUtils.requireTenantId();
            for (Store s : storeRepository.findByTenantId(tenantId)) {
                sales.addAll(salesService.getSalesByStore(s.getId(), from, to));
            }
        }
        if (onlyUsername != null) {
            sales.removeIf(s -> s.getUsername() == null || !s.getUsername().equalsIgnoreCase(onlyUsername));
        }
        sales.sort(Comparator.comparing(SaleResponseDTO::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return sales;
    }

    ReportTable buildSalesDetail(List<SaleResponseDTO> sales, Long storeId, LocalDate from, LocalDate to, ReportContext ctx) {
        ReportTable t = new ReportTable("Informe de ventas", subtitle(storeId, sales, from, to, ctx), List.of(
                Column.dateTime("Fecha y hora", 14),
                Column.text("Local", 13),
                Column.text("Turno", 17),
                Column.text("Cajero", 13),
                Column.text("Medio de pago", 11),
                Column.number("Ítems", 7),
                Column.money("Subtotal", 12),
                Column.money("Impuestos", 11),
                Column.money("Total", 12)));
        BigDecimal subtotal = BigDecimal.ZERO, tax = BigDecimal.ZERO, total = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO, card = BigDecimal.ZERO;
        for (SaleResponseDTO s : sales) {
            int items = s.getItems() == null ? 0
                    : s.getItems().stream().mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum();
            t.row(s.getCreatedAt(), s.getStoreName(), s.getShiftCode(), s.getUsername(),
                    ReportService_paymentLabel(s.getPaymentMethod()), items,
                    nz(s.getSubtotal()), nz(s.getIsv()), nz(s.getTotal()));
            subtotal = subtotal.add(nz(s.getSubtotal()));
            tax = tax.add(nz(s.getIsv()));
            total = total.add(nz(s.getTotal()));
            cash = cash.add(cashPart(s));
            card = card.add(cardPart(s));
        }
        t.total("Cantidad de ventas", sales.size(), ColType.NUMBER);
        t.total("Subtotal", subtotal, ColType.MONEY);
        t.total("Impuestos", tax, ColType.MONEY);
        t.total("Total vendido", total, ColType.MONEY);
        t.total("Cobrado en efectivo", cash, ColType.MONEY);
        t.total("Cobrado con tarjeta", card, ColType.MONEY);
        return t;
    }

    ReportTable buildProductsSold(List<SaleResponseDTO> sales, Long storeId, LocalDate from, LocalDate to, ReportContext ctx) {
        record Acc(String name, long qty, BigDecimal amount) {}
        Map<String, Acc> byProduct = new LinkedHashMap<>();
        for (SaleResponseDTO s : sales) {
            if (s.getItems() == null) continue;
            for (SaleItemDTO i : s.getItems()) {
                String key = i.getProductId() != null ? "id:" + i.getProductId() : "name:" + i.getProductName();
                int q = i.getQuantity() == null ? 0 : i.getQuantity();
                byProduct.merge(key, new Acc(i.getProductName(), q, nz(i.getSubtotal())),
                        (a, b) -> new Acc(a.name(), a.qty() + b.qty(), a.amount().add(b.amount())));
            }
        }
        List<Acc> rows = new ArrayList<>(byProduct.values());
        rows.sort(Comparator.comparing(Acc::amount).reversed());
        BigDecimal grand = rows.stream().map(Acc::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        ReportTable t = new ReportTable("Productos vendidos", subtitle(storeId, sales, from, to, ctx), List.of(
                Column.number("#", 5),
                Column.text("Producto", 34),
                Column.number("Cantidad", 10),
                Column.money("Importe", 14),
                Column.number("% del total", 10)));
        int pos = 1;
        for (Acc a : rows) {
            BigDecimal pct = grand.signum() == 0 ? BigDecimal.ZERO
                    : a.amount().multiply(BigDecimal.valueOf(100)).divide(grand, 1, java.math.RoundingMode.HALF_UP);
            t.row(pos++, a.name(), a.qty(), a.amount(), pct);
        }
        t.total("Productos distintos", rows.size(), ColType.NUMBER);
        t.total("Unidades vendidas", rows.stream().mapToLong(Acc::qty).sum(), ColType.NUMBER);
        t.total("Total vendido (sin impuestos)", grand, ColType.MONEY);
        return t;
    }

    private String subtitle(Long storeId, List<SaleResponseDTO> sales, LocalDate from, LocalDate to, ReportContext ctx) {
        String store = storeId == null ? "Todos los locales"
                : sales.stream().map(SaleResponseDTO::getStoreName).filter(n -> n != null).findFirst()
                        .orElseGet(() -> storeRepository.findById(storeId).map(Store::getName).orElse("Local " + storeId));
        return store + " · Período: " + ctx.date(from) + " al " + ctx.date(to);
    }

    /** Parte en efectivo: CASH = total; MIXED = cashAmount; CARD = 0. */
    static BigDecimal cashPart(SaleResponseDTO s) {
        if ("CARD".equals(s.getPaymentMethod())) return BigDecimal.ZERO;
        if ("MIXED".equals(s.getPaymentMethod())) return nz(s.getCashAmount());
        return nz(s.getTotal());
    }

    /** Parte con tarjeta: CARD = total; MIXED = cardAmount; resto = 0. */
    static BigDecimal cardPart(SaleResponseDTO s) {
        if ("CARD".equals(s.getPaymentMethod())) return nz(s.getTotal());
        if ("MIXED".equals(s.getPaymentMethod())) return nz(s.getCardAmount());
        return BigDecimal.ZERO;
    }

    private static String ReportService_paymentLabel(String method) {
        if (method == null) return "Efectivo";
        return switch (method) {
            case "CARD" -> "Tarjeta";
            case "MIXED" -> "Mixto";
            default -> "Efectivo";
        };
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
