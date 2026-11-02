package balance.reports;

import balance.repository.StoreRepository;
import balance.sales.dto.SaleItemDTO;
import balance.sales.dto.SaleResponseDTO;
import balance.sales.service.SalesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Informes de ventas: detalle, productos vendidos, totales por medio de pago y alcance del cajero. */
@ExtendWith(MockitoExtension.class)
class SalesReportServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 9, 30);

    @InjectMocks private SalesReportService service;
    @Mock private SalesService salesService;
    @Mock private StoreRepository storeRepository;
    @Mock private ReportContextFactory contextFactory;
    @Spy  private ReportRenderer renderer = new ReportRenderer();

    private ReportContext ctx() {
        return new ReportContext("Almacén Don Pepe", null, null, "PEN",
                ZoneId.of("America/Lima"), LocalDateTime.of(2026, 10, 1, 9, 0));
    }

    private SaleItemDTO item(long productId, String name, int qty, String subtotal) {
        SaleItemDTO i = new SaleItemDTO();
        ReflectionTestUtils.setField(i, "productId", productId);
        ReflectionTestUtils.setField(i, "productName", name);
        ReflectionTestUtils.setField(i, "quantity", qty);
        ReflectionTestUtils.setField(i, "subtotal", new BigDecimal(subtotal));
        return i;
    }

    private SaleResponseDTO sale(String user, String method, String subtotal, String tax, String total,
                                 String cash, String card, LocalDateTime createdAtUtc, SaleItemDTO... items) {
        SaleResponseDTO s = new SaleResponseDTO();
        ReflectionTestUtils.setField(s, "storeName", "Centro");
        ReflectionTestUtils.setField(s, "shiftCode", "T-20260908-1343-CEN");
        ReflectionTestUtils.setField(s, "username", user);
        ReflectionTestUtils.setField(s, "paymentMethod", method);
        ReflectionTestUtils.setField(s, "subtotal", new BigDecimal(subtotal));
        ReflectionTestUtils.setField(s, "isv", new BigDecimal(tax));
        ReflectionTestUtils.setField(s, "total", new BigDecimal(total));
        ReflectionTestUtils.setField(s, "cashAmount", cash == null ? null : new BigDecimal(cash));
        ReflectionTestUtils.setField(s, "cardAmount", card == null ? null : new BigDecimal(card));
        ReflectionTestUtils.setField(s, "createdAt", createdAtUtc);
        ReflectionTestUtils.setField(s, "items", List.of(items));
        return s;
    }

    private List<SaleResponseDTO> sample() {
        return List.of(
                sale("ana", "CASH", "100.00", "18.00", "118.00", null, null,
                        LocalDateTime.of(2026, 9, 8, 18, 30), item(1, "Arroz 1kg", 2, "60.00"), item(2, "Aceite", 1, "40.00")),
                sale("beto", "CARD", "50.00", "9.00", "61.00", null, null,
                        LocalDateTime.of(2026, 9, 8, 19, 0), item(1, "Arroz 1kg", 1, "30.00"), item(3, "Sal", 2, "20.00")),
                sale("ana", "MIXED", "40.00", "7.20", "48.20", "20.00", "28.20",
                        LocalDateTime.of(2026, 9, 9, 15, 0), item(2, "Aceite", 1, "40.00")));
    }

    @Test
    void salesDetail_csv_hasRowsInCompanyTime_andTotalsByPaymentMethod() throws Exception {
        when(salesService.getSalesByStore(1L, FROM, TO)).thenReturn(sample());
        when(contextFactory.current()).thenReturn(ctx());

        String csv = new String(service.salesDetail(1L, FROM, TO, null, ReportFormat.CSV), StandardCharsets.UTF_8);

        // 18:30 UTC = 13:30 en Lima (UTC-5)
        assertThat(csv).contains("08/09/2026 13:30;Centro;T-20260908-1343-CEN;ana;Efectivo;3;100,00;18,00;118,00");
        assertThat(csv).contains("Cantidad de ventas;3");
        assertThat(csv).contains("Total vendido;227,20");
        assertThat(csv).contains("Cobrado en efectivo;138,00");   // 118 + 20 del mixto
        assertThat(csv).contains("Cobrado con tarjeta;89,20");    // 61 + 28,20 del mixto
        assertThat(csv).contains("Impuestos;34,20");
    }

    @Test
    void salesDetail_cashierScope_onlyIncludesOwnSales() throws Exception {
        when(salesService.getSalesByStore(1L, FROM, TO)).thenReturn(sample());
        when(contextFactory.current()).thenReturn(ctx());

        String csv = new String(service.salesDetail(1L, FROM, TO, "BETO", ReportFormat.CSV), StandardCharsets.UTF_8);

        assertThat(csv).contains(";beto;").doesNotContain(";ana;");
        assertThat(csv).contains("Cantidad de ventas;1");
    }

    @Test
    void productsSold_groupsByProduct_sortedByAmount() throws Exception {
        when(salesService.getSalesByStore(1L, FROM, TO)).thenReturn(sample());
        when(contextFactory.current()).thenReturn(ctx());

        String csv = new String(service.productsSold(1L, FROM, TO, null, ReportFormat.CSV), StandardCharsets.UTF_8);

        assertThat(csv).contains("1;Arroz 1kg;3;90,00;47,4");
        assertThat(csv).contains("2;Aceite;2;80,00;42,1");
        assertThat(csv).contains("3;Sal;2;20,00;10,5");
        assertThat(csv).contains("Unidades vendidas;7");
        assertThat(csv).contains("Total vendido (sin impuestos);190,00");
    }

    @Test
    void salesDetail_pdf_isValid() throws Exception {
        when(salesService.getSalesByStore(1L, FROM, TO)).thenReturn(sample());
        when(contextFactory.current()).thenReturn(ctx());

        byte[] pdf = service.salesDetail(1L, FROM, TO, null, ReportFormat.PDF);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }
}
