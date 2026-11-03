package balance.users.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * El backend administra usuarios con el service account del PROPIO realm
 * (client_credentials), nunca con el admin del realm master.
 */
class KeycloakAdminServiceTest {

    private KeycloakAdminService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        service = new KeycloakAdminService();
        ReflectionTestUtils.setField(service, "keycloakUrl", "https://kc.test");
        ReflectionTestUtils.setField(service, "realm", "klaro-dev");
        ReflectionTestUtils.setField(service, "clientId", "belopia-admin");
        ReflectionTestUtils.setField(service, "clientSecret", "s3cr3t");
        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.bindTo(rt).build();
    }

    @Test
    void usesClientCredentialsOfOwnRealm_neverMaster() {
        server.expect(requestTo("https://kc.test/realms/klaro-dev/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "client_credentials",
                        "client_id", "belopia-admin",
                        "client_secret", "s3cr3t")))
                .andRespond(withSuccess("{\"access_token\":\"tok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kc.test/admin/realms/klaro-dev/users/abc"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NO_CONTENT));

        service.deleteUser("abc");

        server.verify();
    }
}
