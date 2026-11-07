package balance.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Límite de pedidos por minuto (mejora continua v4, aprobado por Jorge el 07-Nov-2026).
 *
 * <p>Tres reglas, todas configurables por variable de entorno (ver application.properties):
 * <ul>
 *   <li><b>Con token</b>: {@code RATE_LIMIT_USER_PER_MIN} (300) por usuario, con el {@code sub} del
 *       JWT <b>ya validado</b> por Spring Security. Las cajas de un local detrás del mismo NAT
 *       comparten IP, pero no usuario: una caja no le come el cupo a otra.</li>
 *   <li><b>Sin token</b>: {@code RATE_LIMIT_ANON_IP_PER_MIN} (100, el valor de antes) por IP.</li>
 *   <li><b>Techo anti-abuso</b>: {@code RATE_LIMIT_IP_CEILING_PER_MIN} (1.000) por IP para todos
 *       los pedidos, aunque sean de usuarios distintos (~10 cajas por local).</li>
 * </ul>
 *
 * <p>Dónde corre: dentro de la cadena de Spring Security, justo después de
 * {@code BearerTokenAuthenticationFilter} (ver {@link SecurityConfig}). Así el usuario sale de un
 * token con firma, issuer, vencimiento y azp verificados; un token inválido o vencido corta antes
 * con 401 y nunca cuenta como usuario. Los pedidos sin token pasan por acá antes de la
 * autorización, así que caen en la regla por IP aunque el endpoint después responda 401.
 * No es un {@code @Component} a propósito: un bean Filter se registraría además como filtro del
 * servlet, fuera de la cadena (antes pasaba eso, con {@code @Order(1)}, y corría después de
 * Spring Security sin mirar el token).
 *
 * <p>La IP es {@code request.getRemoteAddr()}: con {@code server.forward-headers-strategy=native}
 * Tomcat la toma de X-Forwarded-For solo cuando la conexión viene de un proxy interno (nginx).
 * Un X-Forwarded-For inventado por el cliente no sirve para saltear el límite.
 *
 * <p>Los buckets viven en cachés acotadas (tamaño máximo y expiración por inactividad), para que
 * miles de IPs o usuarios distintos no hagan crecer la memoria sin límite en un servidor compartido.
 * La respuesta 429 lleva {@code Retry-After} (segundos hasta que vuelva a haber cupo).
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    /** Valores por defecto (tabla aprobada por Jorge). Se usan si falta la variable de entorno. */
    public static final int DEFAULT_USER_PER_MIN = 300;
    public static final int DEFAULT_ANON_IP_PER_MIN = 100;
    public static final int DEFAULT_IP_CEILING_PER_MIN = 1000;

    static final long MAX_TRACKED_KEYS = 50_000;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(10);

    static final String BODY =
            "{\"error\":\"Too Many Requests\",\"message\":\"Límite de peticiones alcanzado. Intentá en un minuto.\"}";

    private final int userPerMin;
    private final int anonIpPerMin;
    private final int ipCeilingPerMin;
    private final TimeMeter timeMeter;

    private final Cache<String, Bucket> users = newCache();
    private final Cache<String, Bucket> anonIps = newCache();
    private final Cache<String, Bucket> ipCeilings = newCache();

    /** Con los valores por defecto. */
    public RateLimitingFilter() {
        this(DEFAULT_USER_PER_MIN, DEFAULT_ANON_IP_PER_MIN, DEFAULT_IP_CEILING_PER_MIN);
    }

    public RateLimitingFilter(int userPerMin, int anonIpPerMin, int ipCeilingPerMin) {
        this(userPerMin, anonIpPerMin, ipCeilingPerMin, TimeMeter.SYSTEM_MILLISECONDS);
    }

    /** Con reloj propio: solo para los tests (tiempo manual, sin esperar un minuto real). */
    RateLimitingFilter(int userPerMin, int anonIpPerMin, int ipCeilingPerMin, TimeMeter timeMeter) {
        this.userPerMin = positivo("RATE_LIMIT_USER_PER_MIN", userPerMin);
        this.anonIpPerMin = positivo("RATE_LIMIT_ANON_IP_PER_MIN", anonIpPerMin);
        this.ipCeilingPerMin = positivo("RATE_LIMIT_IP_CEILING_PER_MIN", ipCeilingPerMin);
        this.timeMeter = timeMeter;
    }

    private static int positivo(String nombre, int valor) {
        if (valor <= 0) {
            throw new IllegalArgumentException(nombre + " tiene que ser mayor que 0 (vino " + valor + ")");
        }
        return valor;
    }

    private static Cache<String, Bucket> newCache() {
        return Caffeine.newBuilder().maximumSize(MAX_TRACKED_KEYS).expireAfterAccess(IDLE_EXPIRY).build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Health checks, métricas internas y Swagger fuera del límite
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.startsWith("/swagger-ui")
                || path.startsWith("/api-docs") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();

        // Primero la regla propia (usuario o IP sin token) y después el techo de la IP: un pedido
        // que ya cortó su propia regla no consume el techo, así un usuario pasado de su cupo no
        // deja sin servicio a las otras cajas del mismo local (misma IP).
        String sub = usuarioValidado();
        ConsumptionProbe propio = sub != null
                ? bucket(users, sub, userPerMin).tryConsumeAndReturnRemaining(1)
                : bucket(anonIps, ip, anonIpPerMin).tryConsumeAndReturnRemaining(1);
        if (!propio.isConsumed()) {
            rechazar(response, propio);
            return;
        }
        ConsumptionProbe techo = bucket(ipCeilings, ip, ipCeilingPerMin).tryConsumeAndReturnRemaining(1);
        if (!techo.isConsumed()) {
            rechazar(response, techo);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * {@code sub} del JWT que Spring Security ya validó (firma, issuer, vencimiento, azp), o null.
     * Nunca se lee el header Authorization acá: un token sin validar no cuenta como usuario.
     */
    private static String usuarioValidado() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            String sub = jwt.getToken().getSubject();
            return sub == null || sub.isBlank() ? null : sub;
        }
        return null;
    }

    private Bucket bucket(Cache<String, Bucket> cache, String key, int perMin) {
        return cache.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(perMin, Refill.greedy(perMin, REFILL_PERIOD)))
                .withCustomTimePrecision(timeMeter)
                .build());
    }

    private static void rechazar(HttpServletResponse response, ConsumptionProbe probe) throws IOException {
        long segundos = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(segundos));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(BODY);
    }

    int userPerMin() {
        return userPerMin;
    }

    int anonIpPerMin() {
        return anonIpPerMin;
    }

    int ipCeilingPerMin() {
        return ipCeilingPerMin;
    }

    long trackedKeys() {
        users.cleanUp();
        anonIps.cleanUp();
        ipCeilings.cleanUp();
        return Math.max(users.estimatedSize(), Math.max(anonIps.estimatedSize(), ipCeilings.estimatedSize()));
    }
}
