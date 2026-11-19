package citypass.loginfederado.token;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceValidatorTest {

    private final AudienceValidator validator =
            new AudienceValidator(Set.of("citypass-reclamos-api", "citypass-admin-api"));

    private static Jwt tokenWithAudience(String... audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("U000042");
        if (audience.length > 0) {
            builder.claim("aud", List.of(audience));
        }
        return builder.build();
    }

    @Test
    void acceptsTokenWithAKnownAudience() {
        assertThat(validator.validate(tokenWithAudience("citypass-reclamos-api")).hasErrors())
                .isFalse();
    }

    @Test
    void acceptsTokenWhenOneOfSeveralAudiencesIsKnown() {
        assertThat(validator.validate(tokenWithAudience("otro-sistema", "citypass-admin-api")).hasErrors())
                .isFalse();
    }

    @Test
    void rejectsTokenWithOnlyUnknownAudiences() {
        var result = validator.validate(tokenWithAudience("otro-sistema"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
                .anySatisfy(error -> assertThat(error.getErrorCode()).isEqualTo("invalid_token"));
    }

    @Test
    void rejectsTokenWithoutAudience() {
        assertThat(validator.validate(tokenWithAudience()).hasErrors()).isTrue();
    }
}
