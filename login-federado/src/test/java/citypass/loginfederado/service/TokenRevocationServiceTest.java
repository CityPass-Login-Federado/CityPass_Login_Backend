package citypass.loginfederado.service;

import citypass.loginfederado.model.RevokedAccessToken;
import citypass.loginfederado.repository.RevokedAccessTokenRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenRevocationServiceTest {

    private static final String SECRET = "01234567890123456789012345678901";

    private RevokedAccessTokenRepository repository;
    private TokenRevocationService service;

    @BeforeEach
    void setUp() {
        repository = mock(RevokedAccessTokenRepository.class);
        service = new TokenRevocationService(repository);
    }

    @Test
    void storesANewRevokedToken() {
        when(repository.existsById("jti-1")).thenReturn(false);

        service.revoke("jti-1", "U000042", Instant.now().plusSeconds(900));

        ArgumentCaptor<RevokedAccessToken> saved = ArgumentCaptor.forClass(RevokedAccessToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getJti()).isEqualTo("jti-1");
        assertThat(saved.getValue().getSub()).isEqualTo("U000042");
    }

    @Test
    void doesNotStoreDuplicatesOrIncompleteEntries() {
        when(repository.existsById("jti-1")).thenReturn(true);

        service.revoke("jti-1", "U000042", Instant.now().plusSeconds(900));
        service.revoke(null, "U000042", Instant.now());
        service.revoke("  ", "U000042", Instant.now());
        service.revoke("jti-2", "U000042", null);

        verify(repository, never()).save(any());
    }

    @Test
    void isRevokedReflectsTheRepository() {
        when(repository.existsById("jti-1")).thenReturn(true);

        assertThat(service.isRevoked("jti-1")).isTrue();
        assertThat(service.isRevoked(null)).isFalse();
        assertThat(service.isRevoked("  ")).isFalse();
    }

    @Test
    void revokesTheJtiOfAPresentedAccessToken() throws Exception {
        when(repository.existsById("jti-abc")).thenReturn(false);
        String raw = signedToken("jti-abc", "U000042", Instant.now().plusSeconds(900));

        service.revokeRawAccessToken(raw);

        verify(repository).save(any(RevokedAccessToken.class));
    }

    @Test
    void ignoresMalformedOrExpiredLessTokens() {
        service.revokeRawAccessToken("no-es-un-jwt");
        service.revokeRawAccessToken(null);
        service.revokeRawAccessToken("");

        verify(repository, never()).save(any());
    }

    @Test
    void revokeAllForSubDeletesBySubject() {
        when(repository.deleteBySub("U000042")).thenReturn(3L);

        assertThat(service.revokeAllForSub("U000042")).isEqualTo(3);
        assertThat(service.revokeAllForSub(null)).isZero();
        verify(repository).deleteBySub("U000042");
    }

    @Test
    void purgeExpiredDeletesOldEntries() {
        when(repository.deleteByExpiresAtBefore(any())).thenReturn(2L);

        assertThatCode(service::purgeExpired).doesNotThrowAnyException();
        verify(repository).deleteByExpiresAtBefore(any());
    }

    private static String signedToken(String jti, String sub, Instant exp) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(jti)
                .subject(sub)
                .expirationTime(Date.from(exp))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));
        return jwt.serialize();
    }
}
