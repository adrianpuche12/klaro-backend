package balance.users.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Servicio que encapsula las llamadas al Keycloak Admin REST API.
 * Permite crear, habilitar, deshabilitar y eliminar usuarios en el realm configurado.
 */
@Service
public class KeycloakAdminService {

    @Value("${keycloak.admin.url}")
    private String keycloakUrl;

    @Value("${keycloak.admin.realm}")
    private String realm;

    @Value("${keycloak.admin.username}")
    private String adminUsername;

    @Value("${keycloak.admin.password}")
    private String adminPassword;

    private final RestTemplate restTemplate = buildRestTemplate();

    /** RestTemplate con timeout de 5s conexión y 15s lectura para evitar bloqueos. */
    private static RestTemplate buildRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        return new RestTemplate(factory);
    }

    // ── Obtener token de admin ────────────────────────────────────────────────

    /** Obtiene un token de acceso del realm master para llamar al Admin API. */
    private String getAdminToken() {
        String tokenUrl = keycloakUrl + "/realms/master/protocol/openid-connect/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id",  "admin-cli");
        body.add("username",   adminUsername);
        body.add("password",   adminPassword);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, request, Map.class);

        if (response.getBody() == null || !response.getBody().containsKey("access_token")) {
            throw new RuntimeException("No se pudo obtener token de administrador de Keycloak");
        }
        return (String) response.getBody().get("access_token");
    }

    // ── Crear usuario ─────────────────────────────────────────────────────────

    /**
     * Crea un usuario en Keycloak con el rol especificado y el atributo tenant_id.
     * El atributo tenant_id es necesario para que el Protocol Mapper lo incluya en el JWT.
     * @return ID del usuario creado en Keycloak (UUID)
     */
    public String createUser(String username, String fullName, String password, String role, Long tenantId) {
        String token = getAdminToken();
        String usersUrl = keycloakUrl + "/admin/realms/" + realm + "/users";

        String[] parts = fullName.trim().split("\\s+", 2);
        String firstName = parts[0];
        String lastName  = parts.length > 1 ? parts[1] : "";

        Map<String, Object> userPayload = Map.of(
            "username",    username.trim().toLowerCase(),
            "firstName",   firstName,
            "lastName",    lastName,
            "enabled",     true,
            "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)),
            "attributes",  Map.of("tenant_id", List.of(tenantId.toString()))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(userPayload, headers);

        try {
            ResponseEntity<Void> response = restTemplate.postForEntity(usersUrl, request, Void.class);

            String location = response.getHeaders().getFirst("Location");
            if (location == null) throw new RuntimeException("Keycloak no retorno el ID del usuario");
            String keycloakId = location.substring(location.lastIndexOf("/") + 1);

            assignRealmRole(token, keycloakId, role);

            return keycloakId;

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new IllegalArgumentException("El username '" + username + "' ya existe en el sistema");
            }
            throw new RuntimeException("Error al crear usuario en Keycloak: " + e.getMessage());
        }
    }

    // ── Asignar rol de realm ──────────────────────────────────────────────────

    /** Asigna un rol de realm al usuario. El rol debe existir en el realm. */
    private void assignRealmRole(String token, String keycloakId, String roleName) {
        // 1. Obtener definición del rol
        String roleUrl = keycloakUrl + "/admin/realms/" + realm + "/roles/" + roleName;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> roleResponse = restTemplate.exchange(
            roleUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class
        );

        if (roleResponse.getBody() == null) {
            throw new RuntimeException("El rol '" + roleName + "' no existe en el realm " + realm);
        }

        Map<String, Object> role = roleResponse.getBody();

        // 2. Asignar el rol al usuario
        String assignUrl = keycloakUrl + "/admin/realms/" + realm + "/users/" + keycloakId + "/role-mappings/realm";
        restTemplate.exchange(
            assignUrl, HttpMethod.POST,
            new HttpEntity<>(List.of(role), headers),
            Void.class
        );
    }

    // ── Habilitar / deshabilitar ──────────────────────────────────────────────

    /** Habilita o deshabilita un usuario en Keycloak (controla si puede iniciar sesión). */
    public void setUserEnabled(String keycloakId, boolean enabled) {
        String token  = getAdminToken();
        String url    = keycloakUrl + "/admin/realms/" + realm + "/users/" + keycloakId;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        restTemplate.exchange(
            url, HttpMethod.PUT,
            new HttpEntity<>(Map.of("enabled", enabled), headers),
            Void.class
        );
    }

    // ── Eliminar usuario ──────────────────────────────────────────────────────

    /** Elimina permanentemente un usuario de Keycloak. */
    public void deleteUser(String keycloakId) {
        String token = getAdminToken();
        String url   = keycloakUrl + "/admin/realms/" + realm + "/users/" + keycloakId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }

    // ── Cambiar contraseña ────────────────────────────────────────────────────

    /** Resetea la contraseña de un usuario en Keycloak. */
    public void resetPassword(String keycloakId, String newPassword) {
        String token = getAdminToken();
        String url   = keycloakUrl + "/admin/realms/" + realm + "/users/" + keycloakId + "/reset-password";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        restTemplate.exchange(
            url, HttpMethod.PUT,
            new HttpEntity<>(Map.of("type", "password", "value", newPassword, "temporary", false), headers),
            Void.class
        );
    }
}
