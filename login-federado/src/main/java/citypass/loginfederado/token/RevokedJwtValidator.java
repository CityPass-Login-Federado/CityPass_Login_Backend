package citypass.loginfederado.token;

import citypass.loginfederado.repository.RevokedAccessTokenRepository;
import citypass.loginfederado.repository.RevokedSubjectCutoffRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

/**
 * Rechaza access tokens cuyo `jti` figura en la denylist de logout o cuyo
 * `sub` tiene un corte de revocación masiva posterior o igual a su `iat`
 * (cambio/recupero de contraseña o baja en el panel).
 */
public class RevokedJwtValidator implements OAuth2TokenValidator<Jwt> {

    private final RevokedAccessTokenRepository repository;
    private final RevokedSubjectCutoffRepository cutoffRepository;

    public RevokedJwtValidator(RevokedAccessTokenRepository repository,
                               RevokedSubjectCutoffRepository cutoffRepository) {
        this.repository = repository;
        this.cutoffRepository = cutoffRepository;
    }

    public RevokedJwtValidator(RevokedAccessTokenRepository repository) {
        this(repository, null);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String jti = token.getId();
        if (jti != null && repository.existsById(jti)) {
            return failure();
        }

        String sub = token.getSubject();
        Instant issuedAt = token.getIssuedAt();
        if (cutoffRepository != null && sub != null && issuedAt != null) {
            var cutoff = cutoffRepository.findById(sub);
            if (cutoff.isPresent() && !issuedAt.isAfter(cutoff.get().getRevokedBefore())) {
                return failure();
            }
        }

        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "El token fue revocado",
                null));
    }
}
