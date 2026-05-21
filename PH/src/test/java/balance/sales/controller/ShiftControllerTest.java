package balance.sales.controller;

import balance.sales.dto.ShiftResponseDTO;
import balance.sales.service.ShiftService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShiftController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class ShiftControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private ShiftService shiftService;

    // â”€â”€ POST /api/v2/stores/{storeId}/shifts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void openShift_returns200WhenSuccessful() throws Exception {
        when(shiftService.openShift(eq(1L), any())).thenReturn(buildShiftResponse("OPEN"));

        mockMvc.perform(post("/api/v2/stores/1/shifts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "cajero01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void openShift_returns400WhenShiftAlreadyOpen() throws Exception {
        when(shiftService.openShift(eq(1L), any()))
                .thenThrow(new IllegalStateException("Ya existe un turno abierto para este local"));

        mockMvc.perform(post("/api/v2/stores/1/shifts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "cajero01"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void openShift_returns400WhenStoreNotFound() throws Exception {
        when(shiftService.openShift(eq(99L), any()))
                .thenThrow(new IllegalArgumentException("Local no encontrado"));

        mockMvc.perform(post("/api/v2/stores/99/shifts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "cajero01"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void openShift_usesDefaultUsernameWhenNotProvided() throws Exception {
        when(shiftService.openShift(eq(1L), eq("unknown"))).thenReturn(buildShiftResponse("OPEN"));

        mockMvc.perform(post("/api/v2/stores/1/shifts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(shiftService).openShift(1L, "unknown");
    }

    // â”€â”€ PUT /api/v2/shifts/{shiftId}/close â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void closeShift_returns200WhenSuccessful() throws Exception {
        when(shiftService.closeShift(1L)).thenReturn(buildShiftResponse("CLOSED"));

        mockMvc.perform(put("/api/v2/shifts/1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void closeShift_returns400WhenAlreadyClosed() throws Exception {
        when(shiftService.closeShift(1L))
                .thenThrow(new IllegalStateException("El turno ya estÃ¡ cerrado"));

        mockMvc.perform(put("/api/v2/shifts/1/close"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // â”€â”€ GET /api/v2/shifts/active/{storeId} â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getActiveShift_returns200WhenShiftExists() throws Exception {
        when(shiftService.getActiveShift(1L)).thenReturn(buildShiftResponse("OPEN"));

        mockMvc.perform(get("/api/v2/shifts/active/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void getActiveShift_returns204WhenNoActiveShift() throws Exception {
        when(shiftService.getActiveShift(1L)).thenReturn(null);

        mockMvc.perform(get("/api/v2/shifts/active/1"))
                .andExpect(status().isNoContent());
    }

    // â”€â”€ GET /api/v2/stores/{storeId}/shifts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getHistory_returns200WithList() throws Exception {
        when(shiftService.getShiftHistory(eq(1L), anyInt(), anyInt()))
                .thenReturn(List.of(buildShiftResponse("OPEN"), buildShiftResponse("CLOSED")));

        mockMvc.perform(get("/api/v2/stores/1/shifts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getHistory_returns200WithEmptyListWhenNoShifts() throws Exception {
        when(shiftService.getShiftHistory(eq(1L), anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v2/stores/1/shifts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // â”€â”€ GET /api/v2/shifts/{shiftId} â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getById_returns200WhenFound() throws Exception {
        when(shiftService.getById(1L)).thenReturn(buildShiftResponse("OPEN"));

        mockMvc.perform(get("/api/v2/shifts/1"))
                .andExpect(status().isOk());
    }

    @Test
    void getById_returns404WhenNotFound() throws Exception {
        when(shiftService.getById(99L))
                .thenThrow(new IllegalArgumentException("Turno no encontrado"));

        mockMvc.perform(get("/api/v2/shifts/99"))
                .andExpect(status().isNotFound());
    }

    // â”€â”€ Helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private ShiftResponseDTO buildShiftResponse(String status) {
        ShiftResponseDTO dto = new ShiftResponseDTO();
        ReflectionTestUtils.setField(dto, "code",      "T-20260514-0900-DAN");
        ReflectionTestUtils.setField(dto, "username",  "cajero01");
        ReflectionTestUtils.setField(dto, "status",    status);
        ReflectionTestUtils.setField(dto, "storeId",   1L);
        ReflectionTestUtils.setField(dto, "storeName", "Danli");
        return dto;
    }
}

