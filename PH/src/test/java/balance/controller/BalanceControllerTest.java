package balance.controller;

import balance.model.Store;
import balance.model.Transaction;
import balance.service.BalanceService;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Finanzas (/transactions), P2: lectura, alta, edición y borrado solo en locales permitidos. */
@WebMvcTest(BalanceController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class BalanceControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private BalanceService balanceService;
    @MockBean private PermissionGuard permissionGuard;

    private static Transaction tx(Long id, Long storeId) {
        Store s = new Store();
        s.setId(storeId);
        Transaction t = new Transaction();
        t.setId(id);
        t.setType("income");
        t.setAmount(BigDecimal.TEN);
        t.setDate(LocalDate.of(2026, 11, 1));
        t.setStore(s);
        return t;
    }

    private void storeNotAllowed(Long storeId) {
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(permissionGuard).assertStoreAllowed(storeId);
    }

    @Test
    void getAll_returnsOnlyTransactionsOfAllowedStores() throws Exception {
        when(permissionGuard.storeScope()).thenReturn(StoreScope.only(List.of(1L)));
        when(balanceService.getAllTransactions()).thenReturn(List.of(tx(1L, 1L), tx(2L, 2L)));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void balance_isCalculatedOverTheUsersStores() throws Exception {
        StoreScope scope = StoreScope.only(List.of(1L));
        when(permissionGuard.storeScope()).thenReturn(scope);
        when(balanceService.calculateBalance(any(), any(), eq(scope))).thenReturn(BigDecimal.ONE);

        mockMvc.perform(get("/transactions/balance").param("startDate", "2026-11-01").param("endDate", "2026-11-30"))
                .andExpect(status().isOk());

        verify(balanceService).calculateBalance(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), scope);
    }

    @Test
    void delete_transactionOfAnotherStore_isForbidden_andNothingIsDeleted() throws Exception {
        when(balanceService.getTransactionById(5L)).thenReturn(Optional.of(tx(5L, 2L)));
        storeNotAllowed(2L);

        mockMvc.perform(delete("/transactions/5")).andExpect(status().isForbidden());

        verify(balanceService, never()).deleteTransaction(anyLong());
    }

    @Test
    void update_transactionOfAnotherStore_isForbidden_andNothingIsSaved() throws Exception {
        when(balanceService.getTransactionById(5L)).thenReturn(Optional.of(tx(5L, 2L)));
        storeNotAllowed(2L);

        mockMvc.perform(put("/transactions/5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"income\",\"amount\":10,\"date\":\"2026-11-01\"}"))
                .andExpect(status().isForbidden());

        verify(balanceService, never()).saveTransaction(any());
    }

    @Test
    void create_inAStoreNotAllowed_isForbidden() throws Exception {
        storeNotAllowed(2L);

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"income\",\"amount\":10,\"date\":\"2026-11-01\",\"store\":{\"id\":2}}"))
                .andExpect(status().isForbidden());

        verify(balanceService, never()).saveTransaction(any());
    }
}
