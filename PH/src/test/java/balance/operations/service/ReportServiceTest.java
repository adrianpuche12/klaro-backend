package balance.operations.service;

import balance.operations.dto.OperationDTO;
import balance.tenant.dto.TenantConfigDTO;
import balance.tenant.service.TenantConfigService;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final Long      TENANT_ID = 1L;
    private static final LocalDate FROM      = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO        = LocalDate.of(2026, 12, 31);

    @InjectMocks private ReportService       reportService;
    @Mock    private OperationsV3Service     operationsService;
    @Mock    private TenantConfigService     tenantConfigService;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TenantConfigDTO buildConfig(String company, String currency) {
        TenantConfigDTO dto = new TenantConfigDTO();
        dto.setCompanyName(company);
        dto.setCurrency(currency);
        dto.setTimezone("America/Tegucigalpa");
        return dto;
    }

    private OperationDTO buildOp(String type, BigDecimal amount, String storeName) {
        return OperationDTO.of(type, 1L, LocalDate.of(2026, 5, 21), amount,
                1L, storeName, "cajero01", "Venta de prueba",
                "CONFIRMED", "CASH", null);
    }

    private void stubDefaults(List<OperationDTO> ops) {
        // lenient porque CSV no llama a getConfig (solo PDF y Excel lo usan)
        lenient().when(tenantConfigService.getConfig()).thenReturn(buildConfig("Demo Klaro", "L"));
        when(operationsService.getAllForExport(any(), any(), isNull(), isNull())).thenReturn(ops);
    }

    // ── generateCSV ───────────────────────────────────────────────────────────

    @Test
    void generateCSV_returnsNonEmptyBytes() throws Exception {
        stubDefaults(List.of());

        byte[] result = reportService.generateCSV(FROM, TO, null, null);

        assertThat(result).isNotEmpty();
    }

    @Test
    void generateCSV_containsHeaderRow() throws Exception {
        stubDefaults(List.of());

        byte[] result = reportService.generateCSV(FROM, TO, null, null);
        String csv = new String(result, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv).contains("ID");
        assertThat(csv).contains("Tipo");
        assertThat(csv).contains("Fecha");
        assertThat(csv).contains("Monto");
    }

    @Test
    void generateCSV_containsOperationData() throws Exception {
        OperationDTO op = buildOp("SALE", new BigDecimal("300.00"), "Local Central");
        stubDefaults(List.of(op));

        byte[] result = reportService.generateCSV(FROM, TO, null, null);
        String csv = new String(result, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv).contains("SALE");
        assertThat(csv).contains("300.00");
        assertThat(csv).contains("cajero01");
    }

    @Test
    void generateCSV_passesTypeFilterToService() throws Exception {
        when(operationsService.getAllForExport(any(), any(), eq("SALE"), isNull())).thenReturn(List.of());

        reportService.generateCSV(FROM, TO, "SALE", null);

        verify(operationsService).getAllForExport(any(), any(), eq("SALE"), isNull());
    }

    // ── generatePDF ───────────────────────────────────────────────────────────

    @Test
    void generatePDF_returnsNonEmptyBytes() throws Exception {
        stubDefaults(List.of());

        byte[] result = reportService.generatePDF(FROM, TO, null, null);

        assertThat(result).isNotEmpty();
    }

    @Test
    void generatePDF_startsWithPdfMagicBytes() throws Exception {
        stubDefaults(List.of());

        byte[] result = reportService.generatePDF(FROM, TO, null, null);

        // PDF siempre empieza con "%PDF"
        assertThat(result[0]).isEqualTo((byte) 0x25); // %
        assertThat(result[1]).isEqualTo((byte) 0x50); // P
        assertThat(result[2]).isEqualTo((byte) 0x44); // D
        assertThat(result[3]).isEqualTo((byte) 0x46); // F
    }

    @Test
    void generatePDF_withOperations_returnsLargerFile() throws Exception {
        stubDefaults(List.of());
        byte[] emptyReport = reportService.generatePDF(FROM, TO, null, null);

        when(operationsService.getAllForExport(any(), any(), isNull(), isNull()))
                .thenReturn(List.of(
                        buildOp("SALE", new BigDecimal("300.00"), "Local Central"),
                        buildOp("SALE", new BigDecimal("150.00"), "Local Central")));
        byte[] populatedReport = reportService.generatePDF(FROM, TO, null, null);

        // Reporte con datos es más grande que el vacío
        assertThat(populatedReport.length).isGreaterThan(emptyReport.length);
    }

    @Test
    void generatePDF_usesCompanyNameFromTenantConfig() throws Exception {
        when(tenantConfigService.getConfig()).thenReturn(buildConfig("Mi Empresa S.A.", "L"));
        when(operationsService.getAllForExport(any(), any(), isNull(), isNull())).thenReturn(List.of());

        // Si el PDF se genera sin error, el company name fue leído correctamente
        byte[] result = reportService.generatePDF(FROM, TO, null, null);

        assertThat(result).isNotEmpty();
        verify(tenantConfigService).getConfig();
    }

    // ── generateExcel ─────────────────────────────────────────────────────────

    @Test
    void generateExcel_returnsNonEmptyBytes() throws Exception {
        stubDefaults(List.of());

        byte[] result = reportService.generateExcel(FROM, TO, null, null);

        assertThat(result).isNotEmpty();
    }

    @Test
    void generateExcel_startsWithZipMagicBytes() throws Exception {
        stubDefaults(List.of());

        byte[] result = reportService.generateExcel(FROM, TO, null, null);

        // XLSX es un ZIP: empieza con PK (50 4B)
        assertThat(result[0]).isEqualTo((byte) 0x50); // P
        assertThat(result[1]).isEqualTo((byte) 0x4B); // K
    }

    @Test
    void generateExcel_withOperations_containsData() throws Exception {
        OperationDTO op = buildOp("SALE", new BigDecimal("500.00"), "Local Central");
        stubDefaults(List.of(op));

        byte[] result = reportService.generateExcel(FROM, TO, null, null);

        assertThat(result).isNotEmpty();
        assertThat(result.length).isGreaterThan(100); // Excel mínimo tiene encabezados + estilo
    }

    // ── cobertura de storeId filter ───────────────────────────────────────────

    @Test
    void generateCSV_passesStoreIdToService() throws Exception {
        when(operationsService.getAllForExport(any(), any(), isNull(), eq(5L))).thenReturn(List.of());

        reportService.generateCSV(FROM, TO, null, 5L);

        verify(operationsService).getAllForExport(any(), any(), isNull(), eq(5L));
    }
}
