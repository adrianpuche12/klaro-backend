package balance.users.controller;

import balance.users.service.RoleService;
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

/** GET /api/v2/roles: antes sin guard; ahora solo root o quien gestiona usuarios. */
@WebMvcTest(RoleController.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class RoleControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private RoleService roleService;

    @Test
    void findAll_byCashier_isForbidden() throws Exception {
        doThrow(new AccessDeniedException("No tenés permiso para ver esta información"))
                .when(roleService).assertCanManageUsers();

        mockMvc.perform(get("/api/v2/roles")).andExpect(status().isForbidden());

        verify(roleService, never()).findAll();
    }

    @Test
    void findAll_byManager_returns200() throws Exception {
        when(roleService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v2/roles")).andExpect(status().isOk());
    }
}
