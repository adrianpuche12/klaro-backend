package balance.catalog.controller;

import balance.catalog.dto.StoreResponseDTO;
import balance.catalog.service.StoreV2Service;
import balance.model.Store;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.StoreScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Selector de locales (P10 del frontend): cada usuario recibe solo sus locales permitidos. */
@WebMvcTest(StoreV2Controller.class)
@org.springframework.context.annotation.Import(balance.config.TestSecurityConfig.class)
class StoreV2ControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private StoreV2Service storeV2Service;
    @MockBean private PermissionGuard permissionGuard;

    private static StoreResponseDTO store(Long id) {
        Store s = new Store();
        s.setId(id);
        s.setName("Local " + id);
        s.setActive(true);
        return StoreResponseDTO.from(s);
    }

    @Test
    void active_returnsOnlyAllowedStores() throws Exception {
        when(permissionGuard.storeScope()).thenReturn(StoreScope.only(List.of(2L)));
        when(storeV2Service.findAllActive()).thenReturn(List.of(store(1L), store(2L), store(3L)));

        mockMvc.perform(get("/api/v2/stores/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(2));
    }

    @Test
    void all_rootSeesEveryStore() throws Exception {
        when(permissionGuard.storeScope()).thenReturn(StoreScope.all());
        when(storeV2Service.findAll()).thenReturn(List.of(store(1L), store(2L)));

        mockMvc.perform(get("/api/v2/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void byId_storeNotAllowed_isForbidden() throws Exception {
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(permissionGuard).assertStoreAllowed(3L);

        mockMvc.perform(get("/api/v2/stores/3")).andExpect(status().isForbidden());

        verify(storeV2Service, never()).findById(any());
    }
}
