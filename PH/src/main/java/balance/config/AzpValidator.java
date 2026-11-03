package balance.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Además del issuer (el realm), el token tiene que haber sido emitido para uno de los
 * clientes de Keycloak permitidos (claim {@code azp}, "authorized party"). Sin esto, un
 * token de cualquier otro cliente del mismo realm (ej. la consola de cuenta o admin-cli)
 * servía para llamar a la API. Keycloak no pone el cliente en {@code aud} por defecto,
 * por eso se valida {@code azp} y no la audiencia.
 */
public class AzpValidator implements OAuth2TokenValidator<Jwt> {

    static final String AZP_CLAIM = "azp";

    private final Set<String> allowedClients;

    public AzpValidator(Set<String> allowedClients) {
        this.allowedClients = Set.copyOf(allowedClients);
    }

    /** Lista separada por comas, como viene de la property {@code app.security.allowed-azp}. */
    public static AzpValidator fromCsv(String csv) {
        return new AzpValidator(Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet()));
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String azp = jwt.getClaimAsString(AZP_CLAIM);
        if (azp != null && allowedClients.contains(azp)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "El token no fue emitido para un cliente permitido (azp)", null));
    }
}
