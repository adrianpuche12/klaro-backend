package balance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${cors.allowed-origins}")
    private String allowedOriginsConfig;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /** Clientes de Keycloak cuyos tokens acepta la API (claim azp), separados por comas. */
    @Value("${app.security.allowed-azp:klaro-frontend}")
    private String allowedAzp;

    // Rate limit (mejora continua v4, aprobado por Jorge el 07-Nov). Los valores vienen de
    // RATE_LIMIT_* en application.properties; si faltan, los de la tabla (RateLimitingFilter).
    @Value("${app.rate-limit.user-per-min:" + RateLimitingFilter.DEFAULT_USER_PER_MIN + "}")
    private int rateLimitUserPerMin;

    @Value("${app.rate-limit.anon-ip-per-min:" + RateLimitingFilter.DEFAULT_ANON_IP_PER_MIN + "}")
    private int rateLimitAnonIpPerMin;

    @Value("${app.rate-limit.ip-ceiling-per-min:" + RateLimitingFilter.DEFAULT_IP_CEILING_PER_MIN + "}")
    private int rateLimitIpCeilingPerMin;

    /**
     * Mejora continua v4 (k6): true solo en el perfil load, donde el backend vive en una red de
     * Docker sin salida y sin puertos publicados. En dev y prod queda en false: /actuator/prometheus
     * no se puede pedir (y además el endpoint no está expuesto).
     */
    @Value("${app.metrics.prometheus-interno:false}")
    private boolean prometheusInterno;

    // Endpoints públicos — no requieren JWT
    private static final String[] PUBLIC_PATHS = {
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/api-docs/**",
        "/v3/api-docs/**",
        "/actuator/health",
        "/actuator/info"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers("/actuator/prometheus").access((a, ctx) -> new AuthorizationDecision(prometheusInterno))
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            // Después de validar el token (el usuario es un sub ya verificado) y antes de la
            // autorización (los pedidos sin token también cuentan, aunque después den 401).
            .addFilterAfter(new RateLimitingFilter(rateLimitUserPerMin, rateLimitAnonIpPerMin, rateLimitIpCeilingPerMin),
                    BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Decoder del JWT: firma (JWKS del realm), vencimiento, issuer y además el cliente que
     * pidió el token ({@link AzpValidator}). Reemplaza al decoder que arma Spring Boot con
     * {@code issuer-uri}, que solo valida el issuer. Es perezoso (SupplierJwtDecoder): la
     * configuración del realm se descarga con el primer token, igual que antes, así que el
     * arranque no depende de que Keycloak esté arriba.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        OAuth2TokenValidator<Jwt> validator = tokenValidator(issuerUri, allowedAzp);
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }

    /**
     * Validaciones del token además de la firma: vencimiento, issuer (el realm) y azp.
     * Público para que los tests HTTP (que firman sus propios JWT) usen exactamente las
     * mismas reglas que producción y solo cambien de dónde sale la clave pública.
     */
    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuerUri, String allowedAzpCsv) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                AzpValidator.fromCsv(allowedAzpCsv));
    }

    /**
     * Convierte los roles del JWT de Keycloak al formato Spring Security.
     * Keycloak pone los roles en realm_access.roles (claim anidado) → ROLE_root, ROLE_admin, ROLE_user.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess == null || !realmAccess.containsKey("roles")) {
                return Collections.emptyList();
            }
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.get("roles");
            return roles.stream()
                    .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                    .collect(Collectors.toList());
        });
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.asList(allowedOriginsConfig.split(","));
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
