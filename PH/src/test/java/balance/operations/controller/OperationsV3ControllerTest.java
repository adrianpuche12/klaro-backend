package balance.operations.controller;

import balance.config.TestSecurityConfig;
import balance.operations.dto.OperationDTO;
import balance.operations.dto.OperationSummaryDTO;
import balance.operations.service.OperationsV3Service;
import balance.operations.service.ReportService;
import balance.users.service.PermissionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OperationsV3Controller.class)
@Import(TestSecurityConfig.class)
class OperationsV3ControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean OperationsV3Service operationsService;
    @MockBean ReportService       reportService;
    @MockBean PermissionGuard     permissionGuard;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OperationDTO buildOp(String type, BigDecimal amount) {
        return OperationDTO.of(type, 1L, LocalDate.of(2026, 5, 21), amount,
                1L, "Local Central", "cajero01", "Operacion de prueba",
                "CONFIRMED", "CASH", null);
    }

    private OperationSummaryDTO buildSummary(BigDecimal income, BigDecimal expense) {
        return new OperationSummaryDTO(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                1, income, expense, BigDecimal.ZERO, BigDecimal.ZERO,
                new LinkedHashMap<>(Map.of("SALE", income)));
    }

    // ── GET /api/v3/operations ────────────────────────────────────────────────

    @Test
    void getOperations_returns200WithEmptyList() throws Exception {
        when(operationsService.getOperations(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v3/operations"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getOperations_returnsListWithOperations() throws Exception {
        when(operationsService.getOperations(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(buildOp("SALE", new BigDecimal("300.00"))));

        mockMvc.perform(get("/api/v3/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("SALE"))
                .andExpect(jsonPath("$[0].amount").value(300.0));
    }

    @Test
    void getOperations_passesDateParamsToService() throws Exception {
        when(operationsService.getOperations(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v3/operations")
                .param("from", "2026-01-01")
                .param("to",   "2026-06-30"))
                .andExpect(status().isOk());

        verify(operationsService).getOperations(
                eq(LocalDate.of(2026, 1, 1)),
                eq(LocalDate.of(2026, 6, 30)),
                eq((String) null), eq((Long) null), anyString(), anyInt(), anyInt());
    }

    @Test
    void getOperations_passesTypeAndStoreIdToService() throws Exception {
        when(operationsService.getOperations(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v3/operations")
                .param("type",    "SALE")
                .param("storeId", "1"))
                .andExpect(status().isOk());

        verify(operationsService).getOperations(
                any(), any(), eq("SALE"), eq(1L), anyString(), anyInt(), anyInt());
    }

    @Test
    void getOperations_passesPaginationParamsToService() throws Exception {
        when(operationsService.getOperations(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v3/operations")
                .param("page", "2")
                .param("size", "50"))
                .andExpect(status().isOk());

        verify(operationsService).getOperations(any(), any(), any(), any(), any(), eq(2), eq(50));
    }

    @Test
    void getOperations_usesDefaultsWhenNoDatesProvided() throws Exception {
        when(operationsService.getOperations(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v3/operations"))
                .andExpect(status().isOk());

        verify(operationsService).getOperations(
                any(LocalDate.class), any(LocalDate.class),
                eq((String) null), eq((Long) null), eq("DATE_DESC"), eq(0), eq(20));
    }

    // ── GET /api/v3/operations/summary ────────────────────────────────────────

    @Test
    void getSummary_returns200() throws Exception {
        when(operationsService.getSummary(any(), any(), any()))
                .thenReturn(buildSummary(new BigDecimal("500.00"), BigDecimal.ZERO));

        mockMvc.perform(get("/api/v3/operations/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(500.0))
                .andExpect(jsonPath("$.totalExpense").value(0.0))
                .andExpect(jsonPath("$.netBalance").value(500.0));
    }

    @Test
    void getSummary_passesStoreIdToService() throws Exception {
        when(operationsService.getSummary(any(), any(), any()))
                .thenReturn(buildSummary(BigDecimal.ZERO, BigDecimal.ZERO));

        mockMvc.perform(get("/api/v3/operations/summary").param("storeId", "5"))
                .andExpect(status().isOk());

        verify(operationsService).getSummary(any(), any(), eq(5L));
    }

    // ── GET /api/v3/operations/export ─────────────────────────────────────────

    @Test
    void export_PDF_returns200WithCorrectContentType() throws Exception {
        when(reportService.generatePDF(any(), any(), any(), any()))
                .thenReturn(new byte[]{37, 80, 68, 70}); // %PDF

        mockMvc.perform(get("/api/v3/operations/export")
                .param("format", "PDF")
                .param("from",   "2026-01-01")
                .param("to",     "2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));
    }

    @Test
    void export_CSV_returns200WithCorrectContentType() throws Exception {
        when(reportService.generateCSV(any(), any(), any(), any()))
                .thenReturn("ID,Tipo\n1,SALE\n".getBytes());

        mockMvc.perform(get("/api/v3/operations/export")
                .param("format", "CSV"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv")));
    }

    @Test
    void export_EXCEL_returns200() throws Exception {
        when(reportService.generateExcel(any(), any(), any(), any()))
                .thenReturn(new byte[]{80, 75, 3, 4}); // PK (xlsx/zip)

        mockMvc.perform(get("/api/v3/operations/export")
                .param("format", "EXCEL"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    void export_whenServiceThrows_returns500() throws Exception {
        when(reportService.generatePDF(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Error generando PDF"));

        mockMvc.perform(get("/api/v3/operations/export").param("format", "PDF"))
                .andExpect(status().isInternalServerError());
    }
}
