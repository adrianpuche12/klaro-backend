package balance.security.http;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate limit por usuario y no por IP (vuelta 4) con la app completa: filtro dentro de la cadena
 * de Spring Security, tokens firmados y validados como en producción.
 *
 * <p>Límites chicos para que el test no dependa del reloj (los de la tabla, 300/100/1.000, los
 * prueba {@code RateLimitingFilterTest} con reloj manual). Cada test usa sus propias IPs y sus
 * propios usuarios: los cupos duran lo que dura el contexto de Spring.
 */
@TestPropertySource(properties = {
        "app.rate-limit.user-per-min=" + RateLimitHttpIT.POR_USUARIO,
        "app.rate-limit.anon-ip-per-min=" + RateLimitHttpIT.SIN_TOKEN,
        "app.rate-limit.ip-ceiling-per-min=" + RateLimitHttpIT.TECHO
})
class RateLimitHttpIT extends HttpSecurityTestBase {

    static final int POR_USUARIO = 20;
    static final int SIN_TOKEN = 10;
    static final int TECHO = 50;

    private static final String RUTA = "/api/v2/stores";

    @Test
    void dosUsuariosDeLaMismaIpTienenCadaUnoSuCupo() {
        String ipDelLocal = "192.0.2.201";
        String cajaUno = Usuario.ROOT_A.token();
        for (int i = 0; i < POR_USUARIO; i++) {
            assertThat(llamarDesde(ipDelLocal, "GET", RUTA, null, cajaUno).status()).as("pedido %d", i).isEqualTo(200);
        }
        Respuesta cortado = hasta429(ipDelLocal, cajaUno, 3);
        assertThat(cortado.retryAfter()).as("Retry-After").isNotNull();
        assertThat(Long.parseLong(cortado.retryAfter())).isPositive();

        // otra caja del mismo local (misma IP): su propio cupo, no le afecta
        Respuesta otraCaja = llamarDesde(ipDelLocal, "GET", RUTA, null, Usuario.ENCARGADO_A.token());
        assertThat(otraCaja.status()).isEqualTo(200);
    }

    @Test
    void sinTokenSeCortaPorIpAunqueElEndpointDe401() {
        String ip = "192.0.2.202";
        for (int i = 0; i < SIN_TOKEN; i++) {
            assertThat(llamarDesde(ip, "GET", RUTA, null, null).status()).as("pedido %d", i).isEqualTo(401);
        }
        Respuesta cortado = hasta429(ip, null, 3);
        assertThat(cortado.retryAfter()).isNotNull();
    }

    @Test
    void elTechoPorIpCortaAunqueSeanUsuariosDistintos() {
        String ip = "192.0.2.203";
        int porUsuario = POR_USUARIO - 2;
        String[] tokens = {Usuario.CAJERO_A.token(), Usuario.CONTADOR_A.token()};
        int enviados = 0;
        for (String t : tokens) {
            for (int i = 0; i < porUsuario; i++, enviados++) {
                assertThat(llamarDesde(ip, "GET", RUTA, null, t).status()).isLessThan(429);
            }
        }
        // el tercer usuario arranca con el cupo propio entero y lo corta el techo de la IP
        String tercero = Usuario.ROOT_B.token();
        int delTercero = 0;
        Respuesta r;
        do {
            r = llamarDesde(ip, "GET", RUTA, null, tercero);
            delTercero++;
            enviados++;
        } while (r.status() != 429 && enviados < TECHO + 5);
        assertThat(r.status()).as("429 por el techo de la IP").isEqualTo(429);
        assertThat(delTercero).as("el tercero no llegó a su propio límite").isLessThan(POR_USUARIO);
        assertThat(r.retryAfter()).isNotNull();
    }

    @Test
    void unTokenInvalidoNuncaCuentaComoUsuario() throws Exception {
        String ip = "192.0.2.204";
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String firmadoPorOtro = firmar(claims(Usuario.ROOT_A).build(), (RSAPrivateKey) gen.generateKeyPair().getPrivate());
        // Spring Security lo corta con 401 antes del filtro: nunca 429, ni como usuario ni como IP
        for (int i = 0; i < SIN_TOKEN + 5; i++) {
            assertThat(llamarDesde(ip, "GET", RUTA, null, firmadoPorOtro).status()).as("pedido %d", i).isEqualTo(401);
        }
        // y tampoco gastó el cupo sin token de la IP
        for (int i = 0; i < SIN_TOKEN; i++) {
            assertThat(llamarDesde(ip, "GET", RUTA, null, null).status()).isEqualTo(401);
        }
        hasta429(ip, null, 3);
    }

    @Test
    void prometheusNoEsAccesibleFueraDelPerfilLoad() {
        int status = llamarDesde("192.0.2.205", "GET", "/actuator/prometheus", null, null).status();
        assertThat(status).isBetween(400, 499);
    }

    /** Sigue pidiendo hasta recibir 429 (a lo sumo {@code margen} pedidos: el cupo se repone de a poco). */
    private Respuesta hasta429(String ip, String token, int margen) {
        Respuesta r = null;
        for (int i = 0; i < margen; i++) {
            r = llamarDesde(ip, "GET", RUTA, null, token);
            if (r.status() == 429) return r;
        }
        assertThat(r.status()).as("esperaba 429 en %d pedidos", margen).isEqualTo(429);
        return r;
    }
}
