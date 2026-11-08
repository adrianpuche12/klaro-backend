package balance.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mejora continua v4 (k6): el perfil load solo vive en el ambiente descartable, y DEV y PROD
 * arrancan igual que antes si faltan las variables nuevas (rate limit con los valores de la tabla,
 * sin /actuator/prometheus).
 */
class PerfilLoadTest {

    private static Properties perfil(String nombre) throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource(nombre));
    }

    @Test
    void sinVariablesElRateLimitUsaLosValoresDeLaTabla() throws IOException {
        Properties p = perfil("application.properties");
        assertThat(p.getProperty("app.rate-limit.user-per-min")).isEqualTo("${RATE_LIMIT_USER_PER_MIN:300}");
        assertThat(p.getProperty("app.rate-limit.anon-ip-per-min")).isEqualTo("${RATE_LIMIT_ANON_IP_PER_MIN:100}");
        assertThat(p.getProperty("app.rate-limit.ip-ceiling-per-min")).isEqualTo("${RATE_LIMIT_IP_CEILING_PER_MIN:1000}");
        // y los valores por defecto del código (si ni siquiera está la property) son los mismos
        assertThat(RateLimitingFilter.DEFAULT_USER_PER_MIN).isEqualTo(300);
        assertThat(RateLimitingFilter.DEFAULT_ANON_IP_PER_MIN).isEqualTo(100);
        assertThat(RateLimitingFilter.DEFAULT_IP_CEILING_PER_MIN).isEqualTo(1000);
    }

    @Test
    void baseDejaPrometheusApagado() throws IOException {
        Properties p = perfil("application.properties");
        assertThat(p.getProperty("management.prometheus.metrics.export.enabled")).isEqualTo("false");
        assertThat(p.getProperty("app.metrics.prometheus-interno")).isEqualTo("false");
        assertThat(p.getProperty("management.endpoints.web.exposure.include")).isNull();
    }

    @Test
    void devYProdNoCambian() throws IOException {
        for (String nombre : new String[]{"application-dev.properties", "application-prod.properties"}) {
            Properties p = perfil(nombre);
            assertThat(p.stringPropertyNames()).as(nombre)
                    .noneMatch(k -> k.contains("prometheus") || k.startsWith("management.")
                            || k.startsWith("app.rate-limit") || k.startsWith("spring.datasource.hikari"));
        }
    }

    @Test
    void perfilLoadPrendeMetricasYApagaTerceros() throws IOException {
        Properties p = perfil("application-load.properties");
        assertThat(p.getProperty("management.endpoints.web.exposure.include")).contains("prometheus");
        assertThat(p.getProperty("management.prometheus.metrics.export.enabled")).isEqualTo("true");
        assertThat(p.getProperty("app.metrics.prometheus-interno")).isEqualTo("true");
        assertThat(p.getProperty("sentry.dsn")).isEmpty();
        assertThat(p.getProperty("spring.datasource.url")).contains("postgres:5432/belopia_load")
                .doesNotContain("62.171.160.238").doesNotContain("belopia-postgres");
        assertThat(p.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("${LOAD_DB_POOL_MAX:10}");
        // el rate limit NO se sube en el perfil: lo decide el ambiente por variable (rate-limit.js usa los de PROD)
        assertThat(p.stringPropertyNames()).noneMatch(k -> k.startsWith("app.rate-limit"));
    }
}
