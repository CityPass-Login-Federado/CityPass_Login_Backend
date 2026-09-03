package citypass.loginfederado.panel;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PanelAuthorizationTest {
    private final PanelAuthorization authorization = new PanelAuthorization();

    private Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "RS256"), claims);
    }

    private Map<String, Object> validClaims() {
        return Map.of("aud", List.of("citypass-admin-api"), "token_use", "human", "ver", 1L,
                "groups", List.of("delegados"), "module", "Reclamos", "preferred_username", "admin", "sub", "U000001");
    }

    @Test
    void acceptsValidDelegateAndNormalizesModule() {
        var d = authorization.requireDelegate(jwt(validClaims()));
        assertThat(d.sub()).isEqualTo("U000001");
        assertThat(d.uid()).isEqualTo("admin");
        assertThat(d.module()).isEqualTo("reclamos");
    }

    @Test
    void rejectsWrongAudience() {
        var claims = new java.util.HashMap<>(validClaims()); claims.put("aud", List.of("other"));
        assertDenied(claims);
    }

    @Test
    void rejectsServiceToken() {
        var claims = new java.util.HashMap<>(validClaims()); claims.put("token_use", "service");
        assertDenied(claims);
    }

    @Test
    void rejectsUnsupportedVersion() {
        var claims = new java.util.HashMap<>(validClaims()); claims.put("ver", 2L);
        assertDenied(claims);
    }

    @Test
    void rejectsMissingDelegateGroup() {
        var claims = new java.util.HashMap<>(validClaims()); claims.put("groups", List.of("soporte"));
        assertDenied(claims);
    }

    @Test
    void rejectsBlankModule() {
        var claims = new java.util.HashMap<>(validClaims()); claims.put("module", " ");
        assertDenied(claims);
    }

    private void assertDenied(Map<String, Object> claims) {
        assertThatThrownBy(() -> authorization.requireDelegate(jwt(claims)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
