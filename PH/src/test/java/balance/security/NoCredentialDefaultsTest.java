package balance.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los repos son públicos: ningún properties versionado puede traer un valor por
 * defecto para una contraseña, secreto o clave (ej. ${X_PASSWORD:literal}). Si la
 * variable falta, el backend tiene que NO arrancar, nunca usar un literal del repo.
 * Se permite encadenar otra variable (${DB_MIGRATION_PASSWORD:${DB_PASSWORD}}).
 */
class NoCredentialDefaultsTest {

    private static final Pattern SECRET_WITH_DEFAULT = Pattern.compile(
            "^\\s*([\\w.-]*(password|secret|access-key|token|api-key)[\\w.-]*)\\s*=\\s*\\$\\{[A-Z0-9_]+:(?!\\$\\{)([^}]+)}",
            Pattern.CASE_INSENSITIVE);

    @Test
    void versionedPropertiesHaveNoCredentialDefaults() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources"))) {
            for (Path p : files.filter(f -> f.getFileName().toString()
                    .matches("application(-(?!local)\\w+)?\\.properties")).toList()) {
                for (String line : Files.readAllLines(p)) {
                    Matcher m = SECRET_WITH_DEFAULT.matcher(line);
                    if (m.find()) offenders.add(p.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertThat(offenders).as("propiedades con credencial por defecto").isEmpty();
    }

    @Test
    void detectorCatchesALiteralDefault() {
        assertThat(SECRET_WITH_DEFAULT.matcher("keycloak.admin.password=${KC_PASS:hunter2}").find()).isTrue();
        assertThat(SECRET_WITH_DEFAULT.matcher("spring.flyway.password=${DB_MIGRATION_PASSWORD:${DB_PASSWORD}}").find()).isFalse();
        assertThat(SECRET_WITH_DEFAULT.matcher("keycloak.admin.client-secret=${KEYCLOAK_ADMIN_CLIENT_SECRET}").find()).isFalse();
    }
}
