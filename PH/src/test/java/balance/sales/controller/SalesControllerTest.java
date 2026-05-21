package balance.sales.controller;

import balance.sales.dto.*;
import balance.sales.service.SalesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SalesController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class SalesControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private SalesService salesService;

    // â”€â”€ POST /api/v2/shifts/{shiftId}/sales â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void createSale_returns200WhenSuccessful() throws Exception {
        SaleResponseDTO response = buildSaleResponse();
        when(salesService.createSale(eq(1L), any())).thenReturn(response);

        String body = objectMapper.writeValueAsString(Map.of(
                "username", "cajero01",
                "items", List.of(Map.of("productId", 1, "quantity", 2))
        ));

        mockMvc.perform(post("/api/v2/shifts/1/sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void createSale_returns400WhenShiftClosed() throws Exception {
        when(salesService.createSale(eq(1L), any()))
                .thenThrow(new IllegalStateException("El turno ya estÃ¡ cerrado"));

        String body = objectMapper.writeValueAsString(Map.of(
                "username", "cajero01",
                "items", List.of(Map.of("productId", 1, "quantity", 1))
        ));

        mockMvc.perform(post("/api/v2/shifts/1/sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createSale_returns400WhenProductNotFound() throws Exception {
        when(salesService.createSale(eq(1L), any()))
                .thenThrow(new IllegalArgumentException("Producto no encontrado: 99"));

        String body = objectMapper.writeValueAsString(Map.of(
                "username", "cajero01",
                "items", List.of(Map.of("productId", 99, "quantity", 1))
        ));

        mockMvc.perform(post("/api/v2/shifts/1/sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Producto no encontrado: 99"));
    }

    @Test
    void createSale_returns400WhenValidationFails() throws Exception {
        // username es @NotBlank â€” enviar sin username â†’ 400
        String body = objectMapper.writeValueAsString(Map.of(
                "items", List.of(Map.of("productId", 1, "quantity", 1))
        ));

        mockMvc.perform(post("/api/v2/shifts/1/sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(salesService);
    }

    // â”€â”€ GET /api/v2/shifts/{shiftId}/sales â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getSalesByShift_returns200WithList() throws Exception {
        when(salesService.getSalesByShift(1L)).thenReturn(List.of(buildSaleResponse()));

        mockMvc.perform(get("/api/v2/shifts/1/sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getSalesByShift_returns200WithEmptyList() throws Exception {
        when(salesService.getSalesByShift(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v2/shifts/1/sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // â”€â”€ GET /api/v2/sales/{saleId} â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getSale_returns200WhenFound() throws Exception {
        when(salesService.getSaleById(1L)).thenReturn(buildSaleResponse());

        mockMvc.perform(get("/api/v2/sales/1"))
                .andExpect(status().isOk());
    }

    @Test
    void getSale_returns404WhenNotFound() throws Exception {
        when(salesService.getSaleById(99L))
                .thenThrow(new IllegalArgumentException("Venta no encontrada"));

        mockMvc.perform(get("/api/v2/sales/99"))
                .andExpect(status().isNotFound());
    }

    // â”€â”€ DELETE /api/v2/sales/{saleId} â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void cancelSale_returns204WhenSuccessful() throws Exception {
        doNothing().when(salesService).cancelSale(1L);

        mockMvc.perform(delete("/api/v2/sales/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelSale_returns400WhenConfirmed() throws Exception {
        doThrow(new IllegalStateException("No se puede cancelar una venta ya confirmada"))
                .when(salesService).cancelSale(1L);

        mockMvc.perform(delete("/api/v2/sales/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void cancelSale_returns400WhenNotFound() throws Exception {
        doThrow(new IllegalArgumentException("Venta no encontrada"))
                .when(salesService).cancelSale(99L);

        mockMvc.perform(delete("/api/v2/sales/99"))
                .andExpect(status().isBadRequest());
    }

    // â”€â”€ GET /api/v2/shifts/{shiftId}/summary â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getSummary_returns200WhenFound() throws Exception {
        DailySummaryDTO summary = new DailySummaryDTO(
                LocalDate.now(), 1L, "Danli", 5,
                new BigDecimal("1000.00"), new BigDecimal("0.00"),
                new BigDecimal("1000.00"), new BigDecimal("800.00"),
                new BigDecimal("200.00"), List.of()
        );
        when(salesService.getDailySummary(1L)).thenReturn(summary);

        mockMvc.perform(get("/api/v2/shifts/1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSales").value(5));
    }

    @Test
    void getSummary_returns400WhenShiftNotFound() throws Exception {
        when(salesService.getDailySummary(99L))
                .thenThrow(new IllegalArgumentException("Turno no encontrado"));

        mockMvc.perform(get("/api/v2/shifts/99/summary"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // â”€â”€ POST /api/v2/shifts/{shiftId}/closing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void closeShift_returns400WhenNoOpenSales() throws Exception {
        when(salesService.closeShift(eq(1L), any()))
                .thenThrow(new IllegalStateException("No hay ventas abiertas para cerrar en este turno"));

        String body = objectMapper.writeValueAsString(Map.of("username", "admin"));

        mockMvc.perform(post("/api/v2/shifts/1/closing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // â”€â”€ Helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private SaleResponseDTO buildSaleResponse() {
        SaleResponseDTO dto = new SaleResponseDTO();
        ReflectionTestUtils.setField(dto, "status",   "OPEN");
        ReflectionTestUtils.setField(dto, "username", "cajero01");
        ReflectionTestUtils.setField(dto, "subtotal", new BigDecimal("200.00"));
        ReflectionTestUtils.setField(dto, "isv",      new BigDecimal("30.00"));
        ReflectionTestUtils.setField(dto, "total",    new BigDecimal("230.00"));
        ReflectionTestUtils.setField(dto, "items",    List.of());
        return dto;
    }
}

