package balance.inventory.controller;

import balance.inventory.dto.*;
import balance.inventory.service.InventoryService;
import balance.users.service.PermissionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class InventoryControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private InventoryService inventoryService;
    @MockBean private PermissionGuard  permissionGuard; // no-op por defecto en Mockito (void) -- equivale a usuario legacy

    // â”€â”€ GET /api/v2/stores/{storeId}/stock â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getStock_returns200WithList() throws Exception {
        when(inventoryService.getStock(1L)).thenReturn(List.of(buildStockItem(), buildStockItem()));

        mockMvc.perform(get("/api/v2/stores/1/stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getStock_returns200WithEmptyListWhenNoProducts() throws Exception {
        when(inventoryService.getStock(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v2/stores/1/stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // â”€â”€ GET /api/v2/stores/{storeId}/stock/low â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getLowStock_returns200WithLowStockItems() throws Exception {
        StockItemDTO lowItem = new StockItemDTO();
        ReflectionTestUtils.setField(lowItem, "productId",   1L);
        ReflectionTestUtils.setField(lowItem, "productName", "Pollo");
        ReflectionTestUtils.setField(lowItem, "quantity",    2);
        ReflectionTestUtils.setField(lowItem, "minStock",    5);
        ReflectionTestUtils.setField(lowItem, "lowStock",    true);
        when(inventoryService.getLowStock(1L)).thenReturn(List.of(lowItem));

        mockMvc.perform(get("/api/v2/stores/1/stock/low"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].lowStock").value(true));
    }

    @Test
    void getLowStock_returns200WithEmptyListWhenNoLowStock() throws Exception {
        when(inventoryService.getLowStock(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v2/stores/1/stock/low"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // â”€â”€ GET /api/v2/stores/{storeId}/stock/summary â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getSummary_returns200WithSummaryData() throws Exception {
        StockSummaryDTO summary = new StockSummaryDTO(20L, 18L, 2L, 3L, new BigDecimal("15000.00"));
        when(inventoryService.getSummary(1L)).thenReturn(summary);

        mockMvc.perform(get("/api/v2/stores/1/stock/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").value(20))
                .andExpect(jsonPath("$.lowStockCount").value(2));
    }

    // â”€â”€ POST /api/v2/stores/{storeId}/stock/adjustment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void adjust_returns200WhenEntradaSuccessful() throws Exception {
        StockItemDTO updated = new StockItemDTO();
        ReflectionTestUtils.setField(updated, "productId",   1L);
        ReflectionTestUtils.setField(updated, "productName", "Pollo Entero");
        ReflectionTestUtils.setField(updated, "quantity",    15);
        ReflectionTestUtils.setField(updated, "lowStock",    false);
        when(inventoryService.adjust(eq(1L), any())).thenReturn(updated);

        String body = objectMapper.writeValueAsString(Map.of(
                "productId", 1,
                "type", "ENTRADA",
                "quantity", 5,
                "reason", "ReposiciÃ³n"
        ));

        mockMvc.perform(post("/api/v2/stores/1/stock/adjustment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(15));
    }

    @Test
    void adjust_returns400WhenStockInsufficient() throws Exception {
        when(inventoryService.adjust(eq(1L), any()))
                .thenThrow(new IllegalArgumentException("Stock insuficiente para realizar la salida"));

        String body = objectMapper.writeValueAsString(Map.of(
                "productId", 1,
                "type", "SALIDA",
                "quantity", 999
        ));

        mockMvc.perform(post("/api/v2/stores/1/stock/adjustment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Stock insuficiente para realizar la salida"));
    }

    @Test
    void adjust_returns400WhenValidationFails() throws Exception {
        // quantity es @Min(1) â€” enviar 0 â†’ 400 por validaciÃ³n de Bean Validation
        String body = objectMapper.writeValueAsString(Map.of(
                "productId", 1,
                "type", "ENTRADA",
                "quantity", 0
        ));

        mockMvc.perform(post("/api/v2/stores/1/stock/adjustment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inventoryService);
    }

    @Test
    void adjust_returns400WhenProductIdMissing() throws Exception {
        // productId es @NotNull â€” omitirlo â†’ 400
        String body = objectMapper.writeValueAsString(Map.of(
                "type", "ENTRADA",
                "quantity", 5
        ));

        mockMvc.perform(post("/api/v2/stores/1/stock/adjustment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inventoryService);
    }

    // â”€â”€ GET /api/v2/stores/{storeId}/stock/movements â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getMovements_returns200WithList() throws Exception {
        when(inventoryService.getMovements(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v2/stores/1/stock/movements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // â”€â”€ Helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private StockItemDTO buildStockItem() {
        StockItemDTO dto = new StockItemDTO();
        ReflectionTestUtils.setField(dto, "productId",   1L);
        ReflectionTestUtils.setField(dto, "productName", "Pollo Entero");
        ReflectionTestUtils.setField(dto, "quantity",    10);
        ReflectionTestUtils.setField(dto, "minStock",    5);
        ReflectionTestUtils.setField(dto, "lowStock",    false);
        ReflectionTestUtils.setField(dto, "price",       new BigDecimal("150.00"));
        ReflectionTestUtils.setField(dto, "storeId",     1L);
        ReflectionTestUtils.setField(dto, "storeName",   "Danli");
        return dto;
    }
}

