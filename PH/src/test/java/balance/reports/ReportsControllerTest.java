package balance.reports;

import balance.tenant.service.TenantConfigService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.ShiftReadScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReportsController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class ReportsControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean SalesReportService salesReports;
    @MockBean PermissionGuard permissionGuard;
    @MockBean TenantConfigService tenantConfigService;

    @BeforeEach
    void today() {
        when(tenantConfigService.today()).thenReturn(LocalDate.of(2026, 9, 20));
    }

    @Test
    void sales_pdf_defaultsToCurrentMonth_andIsAnAttachment() throws Exception {
        when(permissionGuard.shiftReadScope(1L)).thenReturn(ShiftReadScope.ALL);
        when(salesReports.salesDetail(eq(1L), eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 9, 20)),
                isNull(), eq(ReportFormat.PDF))).thenReturn(new byte[]{37, 80, 68, 70});

        mockMvc.perform(get("/api/v3/reports/sales").param("storeId", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("ventas-2026-09-01-al-2026-09-20.pdf")));
    }

    @Test
    void sales_cashier_getsOnlyOwnSales() throws Exception {
        when(permissionGuard.shiftReadScope(1L)).thenReturn(ShiftReadScope.OWN);
        when(permissionGuard.currentUsername()).thenReturn("test.cajero");
        when(salesReports.salesDetail(anyLong(), any(), any(), any(), any())).thenReturn(new byte[]{1});

        mockMvc.perform(get("/api/v3/reports/sales").param("storeId", "1").param("format", "EXCEL"))
                .andExpect(status().isOk());

        verify(salesReports).salesDetail(eq(1L), any(), any(), eq("test.cajero"), eq(ReportFormat.EXCEL));
    }

    @Test
    void sales_withoutSalesHistory_isForbidden() throws Exception {
        doThrow(new AccessDeniedException("No tenés acceso a este módulo"))
                .when(permissionGuard).assertAccess(PermissionModule.SALES_HISTORY, 1L);

        mockMvc.perform(get("/api/v3/reports/sales").param("storeId", "1"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(salesReports);
    }

    @Test
    void productsSold_csv() throws Exception {
        when(permissionGuard.shiftReadScope(1L)).thenReturn(ShiftReadScope.ALL);
        when(salesReports.productsSold(anyLong(), any(), any(), any(), eq(ReportFormat.CSV))).thenReturn("x".getBytes());

        mockMvc.perform(get("/api/v3/reports/products-sold").param("storeId", "1").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".csv")));
    }

    @Test
    void invertedRange_isBadRequest() throws Exception {
        mockMvc.perform(get("/api/v3/reports/sales").param("storeId", "1")
                        .param("from", "2026-09-30").param("to", "2026-09-01"))
                .andExpect(status().isBadRequest());
    }
}
