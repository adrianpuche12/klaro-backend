package balance.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AzpValidatorTest {

    private static Jwt token(String azp) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("kc-uuid")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (azp != null) {
            b.claim("azp", azp);
        }
        return b.build();
    }

    @Test
    void tokenOfTheFrontendClient_isAccepted() {
        assertThat(AzpValidator.fromCsv("klaro-frontend").validate(token("klaro-frontend")).hasErrors()).isFalse();
    }

    @Test
    void tokenOfAnotherClientOfTheSameRealm_isRejected() {
        assertThat(AzpValidator.fromCsv("klaro-frontend").validate(token("account-console")).hasErrors()).isTrue();
        assertThat(AzpValidator.fromCsv("klaro-frontend").validate(token("admin-cli")).hasErrors()).isTrue();
    }

    @Test
    void tokenWithoutAzp_isRejected() {
        assertThat(AzpValidator.fromCsv("klaro-frontend").validate(token(null)).hasErrors()).isTrue();
    }

    @Test
    void severalClientsCanBeAllowed_commaSeparated() {
        AzpValidator validator = AzpValidator.fromCsv(" klaro-frontend , e2e-runner ");
        assertThat(validator.validate(token("e2e-runner")).hasErrors()).isFalse();
        assertThat(validator.validate(token("klaro-frontend")).hasErrors()).isFalse();
    }
}
