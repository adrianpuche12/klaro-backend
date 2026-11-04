package balance.security.http;

import balance.security.http.CrossTenantCatalog.Caso;
import balance.security.http.CrossTenantCatalog.Esperado;
import com.nimbusds.jwt.JWTClaimsSet;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aislamiento entre empresas por HTTP, de punta a punta (filtro de seguridad, TenantFilter,
 * PermissionGuard, servicios y RLS de Postgres): con el token de la empresa A, cada endpoint
 * de negocio apuntado a datos de la empresa B.
 *
 * <p>Para cada caso de {@link CrossTenantCatalog}:
 * <ul>
 *   <li>nunca 2xx con un id de B (403/404; 400 solo donde el contrato lo declara);</li>
 *   <li>nunca 5xx;</li>
 *   <li>nunca aparece en la respuesta un texto de B (marca SECRETOB), ni en un error;</li>
 *   <li>los datos de B quedan idénticos (huella de todas las tablas con tenant_id).</li>
 * </ul>
 * Se corre con el root de A (pasa siempre el PermissionGuard: el aislamiento tiene que salir
 * del tenant) y con el encargado de A (perfil acotado).
 */
class CrossTenantHttpIT extends HttpSecurityTestBase {

    static Stream<Arguments> casos() {
        return Stream.of(Usuario.ROOT_A, Usuario.ENCARGADO_A)
                .flatMap(u -> CrossTenantCatalog.CASOS.stream().map(c -> Arguments.of(u, c)));
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("casos")
    void conTokenDeA_nuncaSeAlcanzaNadaDeB(Usuario usuario, Caso caso) {
        Map<String, String> antes = huellaEmpresaB();

        Respuesta r = llamar(caso.metodo(), caso.ruta(), caso.body(), usuario.token());

        // Todas las verificaciones se reportan juntas (un 400 no tapa una fuga ni un cambio en B).
        String quien = usuario + " " + caso + " -> " + r.resumen();
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(r.contieneMarcaB()).as("FUGA: la respuesta trae datos de la empresa B. %s", quien).isFalse();
        soft.assertThat(huellaEmpresaB()).as("ESCRITURA EN B: los datos de la empresa B cambiaron. %s", quien)
                .isEqualTo(antes);
        soft.assertThat(r.status()).as("nunca 5xx. %s", quien).isLessThan(500);
        switch (caso.esperado()) {
            case RECHAZO -> soft.assertThat(r.status()).as("esperaba 403/404. %s", quien).isIn(403, 404);
            case RECHAZO_O_400 -> soft.assertThat(r.status()).as("esperaba 400/403/404. %s", quien).isIn(400, 403, 404);
            case RECHAZO_O_VACIO -> soft.assertThat(r.status() == 403 || r.status() == 404
                            || (r.status() == 200 && r.body().strip().equals("[]")))
                    .as("esperaba 403/404 o una lista vacía. %s", quien).isTrue();
            case SOLO_PROPIOS -> {
                // El encargado está acotado al local 1: los agregados sin local le dan 403 (falla cerrado).
                if (usuario == Usuario.ROOT_A) {
                    soft.assertThat(r.es2xx()).as("el root de A tiene que poder usar su propio endpoint. %s", quien).isTrue();
                } else {
                    soft.assertThat(r.status()).as(quien).isIn(200, 201, 204, 403);
                }
            }
        }
        soft.assertAll();
    }

    /**
     * Control: los mismos GET con un id de B, pero con el token de B, sí responden 2xx. Así el
     * 403/404 de arriba se debe al aislamiento y no a que el dato no exista en fixture.sql.
     */
    static Stream<Caso> lecturasDeB() {
        return CrossTenantCatalog.CASOS.stream()
                .filter(c -> c.metodo().equals("GET") && c.esperado() != Esperado.SOLO_PROPIOS)
                .filter(c -> !c.endpoint().startsWith("GET /api/v3/tenants")) // solo plataforma
                .filter(c -> !c.ruta().contains("..")); // ruta inválida para cualquiera
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("lecturasDeB")
    void control_conTokenDeB_elMismoGetResponde2xx(Caso caso) {
        Respuesta r = llamar("GET", caso.ruta(), null, Usuario.ROOT_B.token());
        assertThat(r.es2xx()).as("%s -> %s", caso, r.resumen()).isTrue();
    }

    @Test
    void control_rootDeA_veSuPropioLocal() {
        Respuesta r = llamar("GET", "/api/v2/stores/1", null, Usuario.ROOT_A.token());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("A Centro");
    }

    @Test
    void balance_soloSumaMovimientosDeA() {
        // A: +100 -100 = 0. B: +777 -333 = 444. Si sumara las dos empresas daría 444.
        Respuesta r = llamar("GET", "/transactions/balance?startDate=2020-01-01&endDate=2100-01-01", null,
                Usuario.ROOT_A.token());
        assertThat(r.status()).isEqualTo(200);
        assertThat(new BigDecimal(r.body().trim())).isEqualByComparingTo("0");
    }

    // ── El token tiene que ser válido de verdad (mismas reglas que producción) ──

    @Test
    void sinToken_401() {
        assertThat(llamar("GET", "/api/v2/stores", null, null).status()).isEqualTo(401);
    }

    @Test
    void tokenFirmadoConOtraClave_401() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String token = firmar(claims(Usuario.ROOT_A).build(), (RSAPrivateKey) gen.generateKeyPair().getPrivate());
        assertThat(llamar("GET", "/api/v2/stores", null, token).status()).isEqualTo(401);
    }

    @Test
    void tokenDeOtroCliente_azp_401() {
        assertThat(llamar("GET", "/api/v2/stores", null, tokenCon(claims(Usuario.ROOT_A).claim("azp", "admin-cli"))).status())
                .isEqualTo(401);
    }

    @Test
    void tokenDeOtroRealm_issuer_401() {
        assertThat(llamar("GET", "/api/v2/stores", null,
                tokenCon(claims(Usuario.ROOT_A).issuer("https://keycloak.test/realms/otro"))).status())
                .isEqualTo(401);
    }

    @Test
    void tokenVencido_401() {
        Instant hace = Instant.now().minusSeconds(3600);
        assertThat(llamar("GET", "/api/v2/stores", null, tokenCon(claims(Usuario.ROOT_A)
                .issueTime(Date.from(hace)).expirationTime(Date.from(hace.plusSeconds(60))))).status())
                .isEqualTo(401);
    }

    @Test
    void tokenSinTenant_noVeNada() {
        Respuesta r = llamar("GET", "/api/v2/stores/1", null, tokenCon(claims(Usuario.ROOT_A).claim("tenant_id", null)));
        assertThat(r.status()).isIn(401, 403, 404);
        assertThat(r.body()).doesNotContain("A Centro");
    }

    private static String tokenCon(JWTClaimsSet.Builder claims) {
        return firmar(claims.build(), (RSAPrivateKey) KEYS.getPrivate());
    }

}
