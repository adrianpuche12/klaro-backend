package balance.config;

import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reglas del rate limit (vuelta 4, valores aprobados por Jorge el 07-Nov): 300/min por usuario,
 * 100/min por IP sin token y techo de 1.000/min por IP. Reloj manual: el tiempo no avanza salvo
 * que el test lo pida, así los conteos son exactos.
 */
class RateLimitingFilterTest {

    /** Reloj que solo avanza a mano. */
    static final class RelojManual implements TimeMeter {
        long nanos;

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void avanzar(long segundos) {
            nanos += TimeUnit.SECONDS.toNanos(segundos);
        }
    }

    private final RelojManual reloj = new RelojManual();
    /** Valores por defecto (los de la tabla), con reloj manual. */
    private final RateLimitingFilter filter = new RateLimitingFilter(
            RateLimitingFilter.DEFAULT_USER_PER_MIN, RateLimitingFilter.DEFAULT_ANON_IP_PER_MIN,
            RateLimitingFilter.DEFAULT_IP_CEILING_PER_MIN, reloj);

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse pedir(String path, String remoteAddr, String fakeForwardedFor, String sub) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRemoteAddr(remoteAddr);
        if (fakeForwardedFor != null) req.addHeader("X-Forwarded-For", fakeForwardedFor);
        SecurityContextHolder.clearContext();
        if (sub != null) {
            // Lo que deja Spring Security después de validar el token
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(sub), List.of()));
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private int call(String remoteAddr, String fakeForwardedFor) throws Exception {
        return pedir("/api/v2/stores", remoteAddr, fakeForwardedFor, null).getStatus();
    }

    private int comoUsuario(String sub, String remoteAddr) throws Exception {
        return pedir("/api/v2/stores", remoteAddr, null, sub).getStatus();
    }

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    @Test
    void sinVariablesDeEntornoLosValoresSonLosDeLaTabla() {
        RateLimitingFilter porDefecto = new RateLimitingFilter();
        assertThat(porDefecto.userPerMin()).isEqualTo(300);
        assertThat(porDefecto.anonIpPerMin()).isEqualTo(100);
        assertThat(porDefecto.ipCeilingPerMin()).isEqualTo(1000);
    }

    @Test
    void valoresInvalidosNoArrancan() {
        assertThatThrownBy(() -> new RateLimitingFilter(0, 100, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RATE_LIMIT_USER_PER_MIN");
        assertThatThrownBy(() -> new RateLimitingFilter(300, -1, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RATE_LIMIT_ANON_IP_PER_MIN");
        assertThatThrownBy(() -> new RateLimitingFilter(300, 100, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RATE_LIMIT_IP_CEILING_PER_MIN");
    }

    @Test
    void sinTokenSeCortaEn100PorIp() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertThat(call("203.0.113.7", null)).isEqualTo(200);
        }
        assertThat(call("203.0.113.7", null)).isEqualTo(429);
    }

    @Test
    void fakeXForwardedFor_doesNotBypassTheLimit() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertThat(call("203.0.113.7", "10.0.0." + i)).isEqualTo(200);
        }
        assertThat(call("203.0.113.7", "198.51.100.99")).isEqualTo(429);
    }

    @Test
    void otherClientsAreNotAffected() throws Exception {
        for (int i = 0; i < 100; i++) call("203.0.113.7", null);
        assertThat(call("203.0.113.7", null)).isEqualTo(429);
        assertThat(call("203.0.113.8", null)).isEqualTo(200);
    }

    @Test
    void dosUsuariosDeLaMismaIpTienenCadaUnoSuCupoDe300() throws Exception {
        String ipDelLocal = "198.51.100.20";
        for (int i = 0; i < 300; i++) {
            assertThat(comoUsuario("caja-1", ipDelLocal)).isEqualTo(200);
        }
        assertThat(comoUsuario("caja-1", ipDelLocal)).isEqualTo(429);
        // la otra caja del mismo local (misma IP) sigue vendiendo
        for (int i = 0; i < 300; i++) {
            assertThat(comoUsuario("caja-2", ipDelLocal)).isEqualTo(200);
        }
        assertThat(comoUsuario("caja-2", ipDelLocal)).isEqualTo(429);
    }

    @Test
    void conTokenNoUsaElCupoSinTokenDeLaIp() throws Exception {
        String ip = "198.51.100.21";
        for (int i = 0; i < 150; i++) {
            assertThat(comoUsuario("caja-1", ip)).isEqualTo(200);
        }
        // los 150 pedidos con token no gastaron los 100 sin token de la IP
        for (int i = 0; i < 100; i++) {
            assertThat(call(ip, null)).isEqualTo(200);
        }
        assertThat(call(ip, null)).isEqualTo(429);
    }

    @Test
    void elTechoPorIpCortaEn1000AunqueSeanUsuariosDistintos() throws Exception {
        String ip = "198.51.100.22";
        for (int caja = 0; caja < 4; caja++) {
            for (int i = 0; i < 250; i++) {
                assertThat(comoUsuario("caja-" + caja, ip)).isEqualTo(200);
            }
        }
        // 1.000 pedidos desde la IP: la caja 5, que no usó nada de su cupo, igual recibe 429
        MockHttpServletResponse res = pedir("/api/v2/stores", ip, null, "caja-5");
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotNull();
        // desde otra IP, la misma caja pasa
        assertThat(comoUsuario("caja-5", "198.51.100.23")).isEqualTo(200);
    }

    @Test
    void unUsuarioPasadoDeSuCupoNoGastaElTechoDelLocal() throws Exception {
        String ip = "198.51.100.24";
        for (int i = 0; i < 2_000; i++) comoUsuario("caja-abusiva", ip);
        // solo los 300 que pasaron cuentan para el techo: las otras cajas siguen
        assertThat(comoUsuario("caja-normal", ip)).isEqualTo(200);
    }

    @Test
    void el429LlevaRetryAfterYElCupoVuelve() throws Exception {
        String ip = "203.0.113.30";
        for (int i = 0; i < 100; i++) call(ip, null);
        MockHttpServletResponse res = pedir("/api/v2/stores", ip, null, null);
        assertThat(res.getStatus()).isEqualTo(429);
        long retryAfter = Long.parseLong(res.getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(res.getContentAsString()).contains("Too Many Requests");

        reloj.avanzar(retryAfter);
        assertThat(call(ip, null)).isEqualTo(200);
        reloj.avanzar(60);
        for (int i = 0; i < 100; i++) {
            assertThat(call(ip, null)).isEqualTo(200);
        }
    }

    @Test
    void unTokenNoAutenticadoNoCuentaComoUsuario() throws Exception {
        String ip = "203.0.113.31";
        JwtAuthenticationToken sinValidar = new JwtAuthenticationToken(jwt("caja-1"), List.of());
        sinValidar.setAuthenticated(false);
        for (int i = 0; i < 101; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v2/stores");
            req.setRemoteAddr(ip);
            SecurityContextHolder.getContext().setAuthentication(sinValidar);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(i < 100 ? 200 : 429);
        }
    }

    @Test
    void healthIsNeverLimited() throws Exception {
        for (int i = 0; i < 1_005; i++) {
            assertThat(pedir("/actuator/health", "203.0.113.9", null, null).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void trackedIpsAreBounded() throws Exception {
        long extra = 2_000;
        for (long i = 0; i < RateLimitingFilter.MAX_TRACKED_KEYS + extra; i++) {
            call("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255), null);
        }
        assertThat(filter.trackedKeys()).isLessThanOrEqualTo(RateLimitingFilter.MAX_TRACKED_KEYS);
    }
}
