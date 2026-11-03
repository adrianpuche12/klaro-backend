package balance.deposit.controller;

import balance.deposit.dto.BankDepositResponseDTO;
import balance.deposit.model.BankDeposit;
import balance.deposit.service.BankDepositService;
import balance.model.Store;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** P8: depósitos bancarios por id validan el local; editar/borrar es de encargado. */
@WebMvcTest(BankDepositController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class BankDepositControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private BankDepositService bankDepositService;
    @MockBean private PermissionGuard permissionGuard;

    private static BankDepositResponseDTO deposit(Long id, Long storeId) {
        Store store = new Store();
        store.setId(storeId);
        store.setName("Local " + storeId);
        BankDeposit d = new BankDeposit();
        d.setId(id);
        d.setStore(store);
        d.setDepositDate(LocalDate.of(2026, 11, 1));
        d.setDeclaredAmount(new BigDecimal("100.00"));
        return BankDepositResponseDTO.of(d, new BigDecimal("100.00"), List.of());
    }

    private void cashierCannotManageStore(Long storeId) {
        doThrow(new AccessDeniedException("Solo un encargado puede hacer esta operación"))
                .when(permissionGuard).assertManage(PermissionModule.OPERATIONS, storeId);
    }

    @Test
    void delete_cashierWithOperations_depositOfAnotherStore_isForbidden_andNothingIsDeleted() {
        when(bankDepositService.findStoreId(5L)).thenReturn(2L);
        cashierCannotManageStore(2L);

        assertForbidden(delete("/api/v3/deposits/5"));

        verify(bankDepositService, never()).delete(any());
    }

    @Test
    void update_cashierWithOperations_isForbidden_andNothingIsUpdated() {
        when(bankDepositService.findStoreId(5L)).thenReturn(2L);
        cashierCannotManageStore(2L);

        assertForbidden(put("/api/v3/deposits/5").contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"x\"}"));

        verify(bankDepositService, never()).update(any(), any());
    }

    @Test
    void delete_managerOfTheStore_returns204() throws Exception {
        when(bankDepositService.findStoreId(5L)).thenReturn(1L);

        mockMvc.perform(delete("/api/v3/deposits/5")).andExpect(status().isNoContent());

        verify(permissionGuard).assertManage(PermissionModule.OPERATIONS, 1L);
        verify(bankDepositService).delete(5L);
    }

    @Test
    void delete_unknownDeposit_returns404() throws Exception {
        when(bankDepositService.findStoreId(5L)).thenThrow(new IllegalArgumentException("Depósito no encontrado"));

        mockMvc.perform(delete("/api/v3/deposits/5")).andExpect(status().isNotFound());

        verify(bankDepositService, never()).delete(any());
    }

    @Test
    void findById_depositOfAStoreNotAllowed_isForbidden() {
        when(bankDepositService.findById(5L)).thenReturn(deposit(5L, 2L));
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(permissionGuard).assertAccess(PermissionModule.OPERATIONS, 2L);

        assertForbidden(get("/api/v3/deposits/5"));
    }

    @Test
    void findAll_withoutStore_returnsOnlyAllowedStores() throws Exception {
        when(permissionGuard.storeScope()).thenReturn(StoreScope.only(List.of(1L)));
        when(bankDepositService.findAll(null)).thenReturn(List.of(deposit(5L, 1L), deposit(6L, 2L)));

        mockMvc.perform(get("/api/v3/deposits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(5));
    }

    private void assertForbidden(org.springframework.test.web.servlet.RequestBuilder request) {
        try {
            mockMvc.perform(request).andExpect(status().isForbidden());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
