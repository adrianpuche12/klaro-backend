package balance.security;

import balance.catalog.controller.StoreV2Controller;
import balance.catalog.dto.StoreResponseDTO;
import balance.catalog.service.StoreV2Service;
import balance.config.SecurityConfig;
import balance.tax.controller.TaxController;
import balance.tax.dto.TaxDTO;
import balance.tax.service.TaxService;
import balance.tenant.controller.TenantConfigController;
import balance.tenant.dto.TenantConfigDTO;
import balance.tenant.service.TenantConfigService;
import balance.users.controller.AppUserController;
import balance.users.service.AppUserService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que los guards @PreAuthorize funcionan correctamente para cada rol.
 * Cada controlador tiene su propio @Nested class.
 * Los servicios están mockeados — solo se testea la capa de seguridad.
 */
class RoleGuardTest {

    // ═══════════════════════════════════════════════════════════════════════
    // StoreV2Controller — solo root puede escribir, todos pueden leer
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @WebMvcTest(StoreV2Controller.class)
    @Import(SecurityConfig.class)
    class StoreGuards {

        @Autowired MockMvc mockMvc;
        @MockBean  StoreV2Service storeV2Service;
        @MockBean  JwtDecoder jwtDecoder;

        @Test void getAll_allowsUser() throws Exception {
            when(storeV2Service.findAll()).thenReturn(List.of());
            mockMvc.perform(get("/api/v2/stores")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_user"))))
                    .andExpect(status().isOk());
        }

        @Test void getAll_allowsAdmin() throws Exception {
            when(storeV2Service.findAll()).thenReturn(List.of());
            mockMvc.perform(get("/api/v2/stores")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                    .andExpect(status().isOk());
        }

        @Test void getAll_allowsRoot() throws Exception {
            when(storeV2Service.findAll()).thenReturn(List.of());
            mockMvc.perform(get("/api/v2/stores")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_root"))))
                    .andExpect(status().isOk());
        }

        @Test void create_deniesUser() throws Exception {
            mockMvc.perform(post("/api/v2/stores")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Local\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_user"))))
                    .andExpect(status().isForbidden());
        }

        @Test void create_deniesAdmin() throws Exception {
            mockMvc.perform(post("/api/v2/stores")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Local\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                    .andExpect(status().isForbidden());
        }

        @Test void create_allowsRoot() throws Exception {
            StoreResponseDTO dto = new StoreResponseDTO();
            when(storeV2Service.create(any())).thenReturn(dto);
            mockMvc.perform(post("/api/v2/stores")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Local Central\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_root"))))
                    .andExpect(status().isCreated());
        }

        @Test void update_deniesAdmin() throws Exception {
            mockMvc.perform(put("/api/v2/stores/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Local\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                    .andExpect(status().isForbidden());
        }

        @Test void delete_deniesAdmin() throws Exception {
            mockMvc.perform(delete("/api/v2/stores/1")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                    .andExpect(status().isForbidden());
        }

        @Test void delete_deniesUser() throws Exception {
            mockMvc.perform(delete("/api/v2/stores/1")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_user"))))
                    .andExpect(status().isForbidden());
        }

        @Test void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v2/stores"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TaxController — solo root puede escribir, todos pueden leer
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @WebMvcTest(TaxController.class)
    @Import(SecurityConfig.class)
    class TaxGuards {

        @Autowired MockMvc mockMvc;
        @MockBean  TaxService taxService;
        @MockBean  JwtDecoder jwtDecoder;

        @Test void getAll_allowsAllRoles() throws Exception {
            when(taxService.findAll()).thenReturn(List.of());
            for (String role : new String[]{"user", "admin", "root"}) {
                mockMvc.perform(get("/api/v3/taxes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                        .andExpect(status().isOk());
            }
        }

        @Test void create_deniesUser() throws Exception {
            mockMvc.perform(post("/api/v3/taxes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"ISV\",\"rate\":15,\"type\":\"PERCENTAGE\",\"appliesTo\":\"ALL\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_user"))))
                    .andExpect(status().isForbidden());
        }

        @Test void create_deniesAdmin() throws Exception {
            mockMvc.perform(post("/api/v3/taxes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"ISV\",\"rate\":15,\"type\":\"PERCENTAGE\",\"appliesTo\":\"ALL\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                    .andExpect(status().isForbidden());
        }

        @Test void create_allowsRoot() throws Exception {
            TaxDTO dto = new TaxDTO(); dto.setName("ISV"); dto.setRate(new BigDecimal("15"));
            dto.setType("PERCENTAGE"); dto.setAppliesTo("ALL");
            when(taxService.create(any())).thenReturn(dto);
            mockMvc.perform(post("/api/v3/taxes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"ISV\",\"rate\":15,\"type\":\"PERCENTAGE\",\"appliesTo\":\"ALL\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_root"))))
                    .andExpect(status().isCreated());
        }

        @Test void toggle_deniesAdmin() throws Exception {
            mockMvc.perform(put("/api/v3/taxes/1/toggle")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                    .andExpect(status().isForbidden());
        }

        @Test void delete_deniesAdmin() throws Exception {
            mockMvc.perform(delete("/api/v3/taxes/1")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                    .andExpect(status().isForbidden());
        }

        @Test void delete_allowsRoot() throws Exception {
            when(taxService.delete(1L)).thenReturn(true);
            mockMvc.perform(delete("/api/v3/taxes/1")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_root"))))
                    .andExpect(status().isNoContent());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TenantConfigController — GET libre para todos, PUT solo root
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @WebMvcTest(TenantConfigController.class)
    @Import(SecurityConfig.class)
    class TenantConfigGuards {

        @Autowired MockMvc mockMvc;
        @MockBean  TenantConfigService tenantConfigService;
        @MockBean  JwtDecoder jwtDecoder;

        @Test void getConfig_allowsUser() throws Exception {
            when(tenantConfigService.getConfig()).thenReturn(new TenantConfigDTO());
            mockMvc.perform(get("/api/v3/tenant/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_user"))))
                    .andExpect(status().isOk());
        }

        @Test void getConfig_allowsAdmin() throws Exception {
            when(tenantConfigService.getConfig()).thenReturn(new TenantConfigDTO());
            mockMvc.perform(get("/api/v3/tenant/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                    .andExpect(status().isOk());
        }

        @Test void updateConfig_deniesUser() throws Exception {
            mockMvc.perform(put("/api/v3/tenant/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"companyName\":\"Test\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_user"))))
                    .andExpect(status().isForbidden());
        }

        @Test void updateConfig_deniesAdmin() throws Exception {
            mockMvc.perform(put("/api/v3/tenant/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"companyName\":\"Test\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                    .andExpect(status().isForbidden());
        }

        @Test void updateConfig_allowsRoot() throws Exception {
            when(tenantConfigService.updateConfig(any())).thenReturn(new TenantConfigDTO());
            mockMvc.perform(put("/api/v3/tenant/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"companyName\":\"Klaro Test\"}")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_root"))))
                    .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AppUserController — SPRINT-14: Keycloak colapsó admin/user -> "staff".
    // El @PreAuthorize acá es un gate grueso (root o staff, cualquiera de los
    // dos entra al controller); la cascada real de niveles (¿este staff en
    // particular puede gestionar usuarios?) vive en RoleService.assertCanManage/
    // assertCanManageUsers, que se llama DENTRO de AppUserService — mockeado acá,
    // así que esa parte fina se prueba en RoleServiceTest y AppUserServiceTest,
    // no en este slice de WebMvcTest.
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @WebMvcTest(AppUserController.class)
    @Import(SecurityConfig.class)
    class AppUserGuards {

        @Autowired MockMvc mockMvc;
        @MockBean  AppUserService userService;
        @MockBean  JwtDecoder jwtDecoder;

        @Test void getAll_deniesUnrecognizedRole() throws Exception {
            // Ninguna cuenta real emite esta autoridad -- confirma que el gate
            // no es "cualquiera autenticado", sigue siendo root/staff únicamente.
            mockMvc.perform(get("/api/v2/users")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_invitado"))))
                    .andExpect(status().isForbidden());
        }

        @Test void getAll_allowsStaff() throws Exception {
            when(userService.findAll()).thenReturn(List.of());
            mockMvc.perform(get("/api/v2/users")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_staff"))))
                    .andExpect(status().isOk());
        }

        @Test void getAll_allowsRoot() throws Exception {
            when(userService.findAll()).thenReturn(List.of());
            mockMvc.perform(get("/api/v2/users")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_root"))))
                    .andExpect(status().isOk());
        }

        @Test void getByUsername_allowsAllRecognizedRoles() throws Exception {
            when(userService.findByUsername("cajero01"))
                    .thenThrow(new IllegalArgumentException("no encontrado"));
            // 404 esperado (usuario no existe en mock) pero NO 401/403
            for (String role : new String[]{"staff", "root"}) {
                mockMvc.perform(get("/api/v2/users/by-username/cajero01")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                        .andExpect(status().isNotFound());
            }
        }

        @Test void delete_deniesUnrecognizedRole() throws Exception {
            mockMvc.perform(delete("/api/v2/users/1")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_invitado"))))
                    .andExpect(status().isForbidden());
        }

        @Test void delete_allowsStaff() throws Exception {
            // Gate grueso del controller únicamente -- la cascada real
            // (¿puede este staff eliminar a ESTE usuario puntual?) la valida
            // RoleService.assertCanManage dentro del service, mockeado acá.
            mockMvc.perform(delete("/api/v2/users/1")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_staff"))))
                    .andExpect(status().isNoContent());
        }

        @Test void delete_allowsRoot() throws Exception {
            mockMvc.perform(delete("/api/v2/users/1")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_root"))))
                    .andExpect(status().isNoContent());
        }
    }
}
