package citypass.loginfederado.token;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;

/**
 * Valida que el `aud` del JWT incluya al menos una audience que este IdP
 * reconoce (las declaradas por sus clientes). Sin esto, el decoder solo
 * verifica firma/issuer/exp: un token legítimamente firmado por el IdP pero
 * emitido para OTRO sistema pasaría como válido acá.
 *
 * aud SIEMPRE es lista en el contrato (specs/03-CONTRATO-TOKEN.md §3).
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final Set<String> knownAudiences;

    public AudienceValidator(Set<String> knownAudiences) {
        this.knownAudiences = Set.copyOf(knownAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audience = token.getAudience();
        if (audience != null && audience.stream().anyMatch(knownAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }

        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "El token no tiene una audience conocida por este IdP",
                null);
        return OAuth2TokenValidatorResult.failure(error);
    }
}
