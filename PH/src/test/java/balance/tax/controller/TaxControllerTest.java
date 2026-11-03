package balance.tax.controller;

import balance.tax.service.TaxService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v3/taxes: antes sin guard; ahora CATALOG o POS (root pasa siempre). */
@WebMvcTest(TaxController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class TaxControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private TaxService taxService;
    @MockBean private PermissionGuard permissionGuard;

    @Test
    void getAll_withoutCatalogOrPos_isForbidden() throws Exception {
        doThrow(new AccessDeniedException("No tenés acceso a este módulo"))
                .when(permissionGuard).assertAccessAny(PermissionModule.CATALOG, PermissionModule.POS);

        mockMvc.perform(get("/api/v3/taxes")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v3/taxes/active")).andExpect(status().isForbidden());

        verifyNoInteractions(taxService);
    }

    @Test
    void getActive_withPos_returns200() throws Exception {
        when(taxService.findActive()).thenReturn(List.of());

        mockMvc.perform(get("/api/v3/taxes/active")).andExpect(status().isOk());
    }
}
