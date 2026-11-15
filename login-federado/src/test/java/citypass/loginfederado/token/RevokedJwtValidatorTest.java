package citypass.loginfederado.token;

import citypass.loginfederado.model.RevokedSubjectCutoff;
import citypass.loginfederado.repository.RevokedAccessTokenRepository;
import citypass.loginfederado.repository.RevokedSubjectCutoffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;

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

    // ── Cutoff (sub + iat) path ───────────────────────────────────────────────

    @Test
    void rejectsTokenWhoseIatIsAtOrBeforeCutoff() {
        RevokedSubjectCutoffRepository cutoffRepo = mock(RevokedSubjectCutoffRepository.class);
        RevokedJwtValidator v = new RevokedJwtValidator(repository, cutoffRepo);

        Instant cutoffTime = Instant.now();
        // iat == revokedBefore  → rejected  (!issuedAt.isAfter(cutoff))
        Instant issuedAt = cutoffTime;
        RevokedSubjectCutoff cutoff = new RevokedSubjectCutoff(
                "U000042", cutoffTime, cutoffTime.plusSeconds(900));
        when(cutoffRepo.findById("U000042")).thenReturn(Optional.of(cutoff));

        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("U000042")
                .issuedAt(issuedAt)
                .claim("jti", "jti-cutoff")
                .build();

        assertThat(v.validate(token).hasErrors()).isTrue();
    }

    @Test
    void acceptsTokenWhoseIatIsAfterCutoff() {
        RevokedSubjectCutoffRepository cutoffRepo = mock(RevokedSubjectCutoffRepository.class);
        RevokedJwtValidator v = new RevokedJwtValidator(repository, cutoffRepo);

        Instant cutoffTime = Instant.now().minusSeconds(60);
        // iat strictly after revokedBefore → accepted
        Instant issuedAt = cutoffTime.plusSeconds(1);
        RevokedSubjectCutoff cutoff = new RevokedSubjectCutoff(
                "U000042", cutoffTime, cutoffTime.plusSeconds(900));
        when(cutoffRepo.findById("U000042")).thenReturn(Optional.of(cutoff));

        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("U000042")
                .issuedAt(issuedAt)
                .claim("jti", "jti-fresh")
                .build();

        assertThat(v.validate(token).hasErrors()).isFalse();
    }

    @Test
    void acceptsTokenWhenNoCutoffExistsForSubject() {
        RevokedSubjectCutoffRepository cutoffRepo = mock(RevokedSubjectCutoffRepository.class);
        RevokedJwtValidator v = new RevokedJwtValidator(repository, cutoffRepo);

        when(cutoffRepo.findById("U000042")).thenReturn(Optional.empty());

        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("U000042")
                .issuedAt(Instant.now())
                .claim("jti", "jti-no-cutoff")
                .build();

        assertThat(v.validate(token).hasErrors()).isFalse();
    }

    @Test
    void acceptsTokenWhenCutoffRepoIsNull() {
        // Single-arg constructor → cutoffRepository == null → branch skipped
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("U000042")
                .issuedAt(Instant.now())
                .build();

        assertThat(validator.validate(token).hasErrors()).isFalse();
    }
}
