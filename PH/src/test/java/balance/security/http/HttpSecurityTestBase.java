package balance.security.http;

import balance.BalanceApplication;
import balance.config.SecurityConfig;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base de los tests HTTP de seguridad: la app completa en un puerto real, contra un Postgres
 * 17 de Testcontainers con las migraciones de Flyway reales y el backend conectado con el rol
 * de aplicación (sin BYPASSRLS, igual que DEV/PROD y que {@code RowLevelSecurityTest}).
 *
 * <p>Los JWT se firman en el test con una clave RSA generada al vuelo. El único cambio
 * respecto de producción es de dónde sale la clave pública: el {@link JwtDecoder} de test
 * valida con {@link SecurityConfig#tokenValidator} (vencimiento + issuer + azp), las mismas
 * reglas que el decoder real contra el JWKS de Keycloak.
 *
 * <p>Antes de cada test se recargan los datos ({@code security-http/fixture.sql}) como dueño
 * de las tablas, así cada caso empieza igual aunque el anterior haya escrito.
 */
@SpringBootTest(classes = BalanceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:application-test.properties", properties = {
        "logging.level.org.springframework.web=WARN",
        "logging.level.org.springframework.web.filter=WARN",
        "logging.level.org.springframework.web.cors=WARN",
        "spring.jpa.show-sql=false"
})
@Import(HttpSecurityTestBase.TestJwtConfig.class)
abstract class HttpSecurityTestBase {

    static final String ISSUER = "https://keycloak.test/realms/belopia-test";
    static final String AZP = "klaro-frontend";
    /** Marca que llevan todos los textos de la empresa B en fixture.sql. */
    static final String MARCA_B = "secretob";

    static final long TENANT_A = 1L;
    static final long TENANT_B = 2L;

    // V11 deriva el rol del nombre de la base: "test" -> "test_app".
    private static final String APP_ROLE = "test_app";
    private static final String APP_PASSWORD = "app_test_pw";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final KeyPair KEYS = rsaKeyPair();
    private static final String FIXTURE = leerRecurso("/security-http/fixture.sql");
    private static final AtomicInteger IP = new AtomicInteger();

    static {
        POSTGRES.start();
        // Paso operativo previo al deploy: el rol existe con LOGIN antes de que V11 corra.
        ejecutarComoDueno("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> APP_ROLE);
        r.add("spring.datasource.password", () -> APP_PASSWORD);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        r.add("app.security.allowed-azp", () -> AZP);
    }

    @TestConfiguration
    static class TestJwtConfig {
        /** Reemplaza al decoder que descarga el JWKS de Keycloak; conserva issuer + azp + vencimiento. */
        @Bean
        @Primary
        JwtDecoder testJwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                                  @Value("${app.security.allowed-azp}") String azp) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(SecurityConfig.tokenValidator(issuer, azp));
            return decoder;
        }
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void recargarDatos() {
        ejecutarComoDueno(FIXTURE);
    }

    // ── Usuarios (tokens) ────────────────────────────────────────────────────

    /** Usuario de un token: coincide con una fila de app_users de fixture.sql (keycloak_id = sub). */
    enum Usuario {
        ROOT_A("kc-root-a", "root.a", TENANT_A, "root"),
        ENCARGADO_A("kc-encargado-a", "encargado.a", TENANT_A, "staff"),
        CAJERO_A("kc-cajero-a", "cajero.a", TENANT_A, "staff"),
        CONTADOR_A("kc-contador-a", "contador.a", TENANT_A, "staff"),
        ROOT_B("kc-root-b", "root.secretob", TENANT_B, "root");

        final String sub;
        final String username;
        final long tenantId;
        final String rolKeycloak;

        Usuario(String sub, String username, long tenantId, String rolKeycloak) {
            this.sub = sub;
            this.username = username;
            this.tenantId = tenantId;
            this.rolKeycloak = rolKeycloak;
        }

        String token() {
            return firmar(claims(this).build(), (RSAPrivateKey) KEYS.getPrivate());
        }
    }

    static JWTClaimsSet.Builder claims(Usuario u) {
        Instant now = Instant.now();
        Map<String, Object> realmAccess = new LinkedHashMap<>();
        realmAccess.put("roles", List.of(u.rolKeycloak, "offline_access"));
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(u.sub)
                .claim("azp", AZP)
                .claim("preferred_username", u.username)
                .claim("tenant_id", String.valueOf(u.tenantId))
                .claim("realm_access", realmAccess)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString());
    }

    static String firmar(JWTClaimsSet claims, RSAPrivateKey key) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    record Respuesta(int status, String body) {
        boolean es2xx() {
            return status >= 200 && status < 300;
        }

        boolean contieneMarcaB() {
            return body != null && body.toLowerCase().contains(MARCA_B);
        }

        String resumen() {
            String b = body == null ? "" : body;
            return status + " " + (b.length() > 400 ? b.substring(0, 400) + "..." : b);
        }
    }

    /** Body especial: multipart con un PNG mínimo en el campo "file" (subida de comprobantes). */
    static final String MULTIPART_PNG = "MULTIPART_PNG";
    private static final String BOUNDARY = "----belopia-test-boundary";

    private static byte[] multipartPng() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
        String head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"c.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String tail = "\r\n--" + BOUNDARY + "--\r\n";
        byte[] h = head.getBytes(StandardCharsets.UTF_8);
        byte[] t = tail.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[h.length + png.length + t.length];
        System.arraycopy(h, 0, all, 0, h.length);
        System.arraycopy(png, 0, all, h.length, png.length);
        System.arraycopy(t, 0, all, h.length + png.length, t.length);
        return all;
    }

    Respuesta llamar(String metodo, String ruta, String body, String token) {
        boolean multipart = MULTIPART_PNG.equals(body);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : multipart
                    ? HttpRequest.BodyPublishers.ofByteArray(multipartPng())
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + ruta))
                .timeout(Duration.ofSeconds(30))
                .method(metodo, publisher)
                // RateLimitingFilter limita 100 pedidos/min por IP. Tomcat (forward-headers-strategy
                // =native) toma X-Forwarded-For porque el test se conecta desde 127.0.0.1, que es un
                // proxy interno: una IP pública de documentación (RFC 5737) distinta cada 50 pedidos.
                .header("X-Forwarded-For", ipDePrueba(IP.getAndIncrement() / 50));
        if (body != null) {
            req.header("Content-Type", multipart ? "multipart/form-data; boundary=" + BOUNDARY : "application/json");
        }
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Respuesta(res.statusCode(), new String(res.body(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String ipDePrueba(int n) {
        String[] redes = {"192.0.2.", "198.51.100.", "203.0.113."};
        return redes[(n / 250) % redes.length] + (n % 250 + 1);
    }

    // ── Base de datos (como dueño, sin RLS) ──────────────────────────────────

    /**
     * Huella de TODOS los datos de la empresa B: una fila por tabla con tenant_id, más su fila
     * de tenants. Si un pedido con token de A cambia algo de B, la huella cambia.
     */
    static Map<String, String> huellaEmpresaB() {
        Map<String, String> huella = new LinkedHashMap<>();
        try (Connection c = conexionDueno(); Statement st = c.createStatement()) {
            List<String> tablas = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("""
                    SELECT c.table_name FROM information_schema.columns c
                    JOIN information_schema.tables t
                      ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                    WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id'
                      AND t.table_type = 'BASE TABLE'
                    ORDER BY c.table_name""")) {
                while (rs.next()) tablas.add(rs.getString(1));
            }
            for (String tabla : tablas) {
                huella.put(tabla, unValor(st, "SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), '')) "
                        + "FROM \"" + tabla + "\" t WHERE tenant_id = " + TENANT_B));
            }
            huella.put("tenants", unValor(st, "SELECT md5(t::text) FROM tenants t WHERE id = " + TENANT_B));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return huella;
    }

    static String unValor(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static void ejecutarComoDueno(String sql) {
        try (Connection c = conexionDueno(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static Connection conexionDueno() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String leerRecurso(String path) {
        try (InputStream in = HttpSecurityTestBase.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("No existe " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
