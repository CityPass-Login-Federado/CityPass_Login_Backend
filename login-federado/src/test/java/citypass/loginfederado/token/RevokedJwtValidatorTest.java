package citypass.loginfederado.token;

import citypass.loginfederado.repository.RevokedAccessTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RevokedJwtValidatorTest {

    private RevokedAccessTokenRepository repository;
    private RevokedJwtValidator validator;

    @BeforeEach
    void setUp() {
        repository = mock(RevokedAccessTokenRepository.class);
        validator = new RevokedJwtValidator(repository);
    }

    private static Jwt tokenWithJti(String jti) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("U000042");
        if (jti != null) {
            builder.claim("jti", jti);
        }
        return builder.build();
    }

    @Test
    void acceptsTokenWhoseJtiIsNotRevoked() {
        when(repository.existsById("jti-1")).thenReturn(false);

        assertThat(validator.validate(tokenWithJti("jti-1")).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWhoseJtiIsRevoked() {
        when(repository.existsById("jti-1")).thenReturn(true);

        var result = validator.validate(tokenWithJti("jti-1"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
                .anySatisfy(error -> assertThat(error.getErrorCode()).isEqualTo("invalid_token"));
    }

    @Test
    void acceptsTokenWithoutJti() {
        assertThat(validator.validate(tokenWithJti(null)).hasErrors()).isFalse();
    }
}
