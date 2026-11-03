package balance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
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
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );

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
