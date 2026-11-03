package balance.controller;

import balance.dto.AllOperationsDTO;
import balance.service.FormsService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.StoreScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** API vieja de Operaciones: P2 (locales), P3 (mine atado al token) y P8 (editar/borrar de encargado). */
@WebMvcTest(OperationsController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class OperationsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private FormsService formsService;
    @MockBean private PermissionGuard permissionGuard;

    private static AllOperationsDTO op(Long id, Long storeId) {
        AllOperationsDTO dto = new AllOperationsDTO();
        dto.setId(id);
        dto.setType("CLOSING");
        dto.setStoreId(storeId);
        dto.setAmount(new BigDecimal("10.00"));
        dto.setDate(LocalDate.of(2026, 11, 1));
        return dto;
    }

    // ── P2: listados filtrados por locales permitidos ─────────────────────────

    @Test
    void all_withoutStore_returnsOnlyOperationsOfAllowedStores() throws Exception {
        when(permissionGuard.storeScope()).thenReturn(StoreScope.only(List.of(1L)));
        when(formsService.getAllOperations()).thenReturn(List.of(op(1L, 1L), op(2L, 2L), op(3L, null)));

        mockMvc.perform(get("/api/operations/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void all_withStoreNotAllowed_isForbidden() throws Exception {
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(permissionGuard).assertAccess(PermissionModule.OPERATIONS, 2L);

        mockMvc.perform(get("/api/operations/all").param("storeId", "2"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(formsService);
    }

    // ── P3: /mine atado al token ──────────────────────────────────────────────

    @Test
    void mine_ofAnotherUser_isForbidden_forNonManager() throws Exception {
        when(permissionGuard.currentUsername()).thenReturn("cajero01");
        when(permissionGuard.canManageUsers()).thenReturn(false);

        mockMvc.perform(get("/api/operations/mine").param("username", "encargado01"))
                .andExpect(status().isForbidden());

        verify(formsService, never()).getOperationsByUsername(any());
    }

    @Test
    void mine_withoutUsername_usesTheTokenUser() throws Exception {
        when(permissionGuard.currentUsername()).thenReturn("cajero01");
        when(permissionGuard.storeScope()).thenReturn(StoreScope.all());
        when(formsService.getOperationsByUsername("cajero01")).thenReturn(List.of(op(1L, 1L)));

        mockMvc.perform(get("/api/operations/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void mine_ofAnotherUser_isAllowed_forManager() throws Exception {
        when(permissionGuard.currentUsername()).thenReturn("encargado01");
        when(permissionGuard.canManageUsers()).thenReturn(true);
        when(permissionGuard.storeScope()).thenReturn(StoreScope.all());
        when(formsService.getOperationsByUsername("cajero01")).thenReturn(List.of());

        mockMvc.perform(get("/api/operations/mine").param("username", "cajero01"))
                .andExpect(status().isOk());
    }

    // ── P8: editar/borrar exige encargado en el local del registro ────────────

    @Test
    void delete_cashierWithOperations_closingOfAnotherStore_isForbidden_andNothingIsDeleted() throws Exception {
        when(formsService.storeIdOf("CLOSING", 7L)).thenReturn(2L);
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(permissionGuard).assertManage(PermissionModule.OPERATIONS, 2L);

        mockMvc.perform(delete("/api/operations/CLOSING/7")).andExpect(status().isForbidden());

        verify(formsService, never()).deleteClosingDeposit(anyLong());
    }

    @Test
    void delete_salaryByManagerOfTheStore_returns204() throws Exception {
        when(formsService.storeIdOf("SALARY", 7L)).thenReturn(1L);

        mockMvc.perform(delete("/api/operations/SALARY/7")).andExpect(status().isNoContent());

        verify(permissionGuard).assertManage(PermissionModule.OPERATIONS, 1L);
        verify(formsService).deleteSalaryPayment(7L);
    }

    @Test
    void delete_gastoAdmin_requiresManagingEveryStoreItWasSplitInto() throws Exception {
        when(formsService.storeIdsOfGastoAdmin(3L)).thenReturn(Set.of(1L, 2L));
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(permissionGuard).assertManage(PermissionModule.OPERATIONS, 2L);

        mockMvc.perform(delete("/api/operations/GASTO_ADMIN/3")).andExpect(status().isForbidden());

        verify(formsService, never()).deleteGastoAdmin(anyLong());
    }

    @Test
    void update_movingToAStoreNotManaged_isForbidden_andNothingIsUpdated() throws Exception {
        when(formsService.storeIdOf("SUPPLIER", 7L)).thenReturn(1L);
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(permissionGuard).assertManage(PermissionModule.OPERATIONS, 2L);

        mockMvc.perform(put("/api/operations/SUPPLIER/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10,\"storeId\":2}"))
                .andExpect(status().isForbidden());

        verify(formsService, never()).updateSupplierPayment(anyLong(), any());
    }
}
