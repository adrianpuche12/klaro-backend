package balance.controller;

import balance.model.ClosingDeposit;
import balance.model.Store;
import balance.model.SupplierPayment;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * /api/forms: P2 (locales permitidos) y P4 (el cajero solo carga "Depósito de Cierres" en
 * sus locales; pagos, salarios y gastos administrativos son de encargado).
 */
@WebMvcTest(FormsController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class FormsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private FormsService formsService;
    @MockBean private PermissionGuard permissionGuard;

    private static final String CLOSING_BODY = """
            {"closingsCount":1,"amount":100.00,"depositDate":"2026-11-01","periodStart":"2026-11-01",
             "periodEnd":"2026-11-01","username":"cajero01","store":{"id":%d}}""";

    private static final String SUPPLIER_BODY = """
            {"supplier":"Pollo Rey","amount":100.00,"paymentDate":"2026-11-01","username":"cajero01",
             "store":{"id":1}}""";

    private static Store store(Long id) {
        Store s = new Store();
        s.setId(id);
        return s;
    }

    @Test
    void closingDeposit_cashierInHisStore_isCreated() throws Exception {
        when(formsService.createClosingDeposit(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/forms/closing-deposits")
                        .contentType(MediaType.APPLICATION_JSON).content(CLOSING_BODY.formatted(1)))
                .andExpect(status().isOk());

        verify(permissionGuard).assertAccess(PermissionModule.OPERATIONS, 1L);
    }

    @Test
    void closingDeposit_inAStoreNotAllowed_isForbidden() throws Exception {
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(permissionGuard).assertAccess(PermissionModule.OPERATIONS, 2L);

        mockMvc.perform(post("/api/forms/closing-deposits")
                        .contentType(MediaType.APPLICATION_JSON).content(CLOSING_BODY.formatted(2)))
                .andExpect(status().isForbidden());

        verify(formsService, never()).createClosingDeposit(any());
    }

    @Test
    void supplierPayment_byCashier_isForbidden_P4() throws Exception {
        // La pantalla solo le habilita "Depósito de Cierres" al cajero; por API tampoco puede.
        doThrow(new AccessDeniedException("Solo un encargado puede hacer esta operación"))
                .when(permissionGuard).assertManage(PermissionModule.OPERATIONS, 1L);

        mockMvc.perform(post("/api/forms/supplier-payments")
                        .contentType(MediaType.APPLICATION_JSON).content(SUPPLIER_BODY))
                .andExpect(status().isForbidden());

        verify(formsService, never()).saveSupplierPayment(any());
    }

    @Test
    void gastoAdmin_byCashier_isForbidden_P4() throws Exception {
        doThrow(new AccessDeniedException("Solo un encargado puede hacer esta operación"))
                .when(permissionGuard).assertManage(PermissionModule.OPERATIONS, 1L);

        mockMvc.perform(post("/api/forms/gasto-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fecha":"2026-11-01","monto":100,"descripcion":"Luz","tipo":"expense",
                                 "distribuciones":[{"storeId":1,"porcentaje":100}]}"""))
                .andExpect(status().isForbidden());

        verify(formsService, never()).saveGastoAdmin(any());
    }

    @Test
    void closingDeposits_all_returnsOnlyAllowedStores() throws Exception {
        ClosingDeposit mine = new ClosingDeposit();
        mine.setId(1L);
        mine.setStore(store(1L));
        ClosingDeposit other = new ClosingDeposit();
        other.setId(2L);
        other.setStore(store(2L));
        when(permissionGuard.storeScope()).thenReturn(StoreScope.only(List.of(1L)));
        when(formsService.getAllClosingDeposits()).thenReturn(List.of(mine, other));

        mockMvc.perform(get("/api/forms/closing-deposits/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void supplierPayments_all_returnsOnlyAllowedStores() throws Exception {
        SupplierPayment mine = new SupplierPayment();
        mine.setId(1L);
        mine.setAmount(BigDecimal.TEN);
        mine.setStore(store(1L));
        SupplierPayment noStore = new SupplierPayment();
        noStore.setId(2L);
        noStore.setAmount(BigDecimal.TEN);
        when(permissionGuard.storeScope()).thenReturn(StoreScope.only(List.of(1L)));
        when(formsService.getAllSupplierPayments()).thenReturn(List.of(mine, noStore));

        mockMvc.perform(get("/api/forms/supplier-payments/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
