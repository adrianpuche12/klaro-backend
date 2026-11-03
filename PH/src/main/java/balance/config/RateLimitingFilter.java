package balance.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * 100 peticiones por minuto por IP.
 *
 * La IP es {@code request.getRemoteAddr()}: con {@code server.forward-headers-strategy=native}
 * Tomcat la toma de X-Forwarded-For solo cuando la conexión viene de un proxy interno
 * (nginx). Un X-Forwarded-For inventado por el cliente no sirve para saltear el límite.
 *
 * Los buckets viven en una caché acotada (tamaño máximo y expiración por inactividad), para
 * que miles de IPs distintas no hagan crecer la memoria sin límite en un servidor compartido.
 */
@Component
@Order(1)
public class RateLimitingFilter implements Filter {

    static final int CAPACITY = 100;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    static final long MAX_TRACKED_IPS = 50_000;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_IPS)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;

        // Excluir health checks y Swagger del rate limiting
        String path = request.getRequestURI();
        if (path.startsWith("/actuator/") || path.startsWith("/swagger-ui") || path.startsWith("/api-docs") || path.startsWith("/v3/api-docs")) {
            chain.doFilter(req, res);
            return;
        }

        Bucket bucket = buckets.get(request.getRemoteAddr(), k -> createBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(req, res);
        } else {
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"Límite de peticiones alcanzado. Intentá en un minuto.\"}");
        }
    }

    private Bucket createBucket() {
        Bandwidth limit = Bandwidth.classic(CAPACITY, Refill.greedy(CAPACITY, REFILL_PERIOD));
        return Bucket.builder().addLimit(limit).build();
    }

    long trackedIps() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
