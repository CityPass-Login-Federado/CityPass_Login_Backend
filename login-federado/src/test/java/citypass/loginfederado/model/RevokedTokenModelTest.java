package citypass.loginfederado.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RevokedTokenModelTest {

    @Test
    void revokedAccessTokenExposesItsData() {
        Instant exp = Instant.now().plusSeconds(900);
        RevokedAccessToken token = new RevokedAccessToken("jti-123", "U000042", exp);

        assertThat(token.getJti()).isEqualTo("jti-123");
        assertThat(token.getSub()).isEqualTo("U000042");
        assertThat(token.getExpiresAt()).isEqualTo(exp);
    }

    @Test
    void revokedSubjectCutoffExposesItsDataAndCanBeAdvanced() {
        Instant before = Instant.now();
        Instant exp = before.plusSeconds(900);
        RevokedSubjectCutoff cutoff = new RevokedSubjectCutoff("U000042", before, exp);

        assertThat(cutoff.getSub()).isEqualTo("U000042");
        assertThat(cutoff.getRevokedBefore()).isEqualTo(before);
        assertThat(cutoff.getExpiresAt()).isEqualTo(exp);

        Instant newBefore = before.plusSeconds(300);
        Instant newExp = exp.plusSeconds(300);
        cutoff.advanceCutoff(newBefore, newExp);

        assertThat(cutoff.getRevokedBefore()).isEqualTo(newBefore);
        assertThat(cutoff.getExpiresAt()).isEqualTo(newExp);
    }

    @Test
    void entitiesHaveJpaNoArgConstructors() throws Exception {
        var tokenCtor = RevokedAccessToken.class.getDeclaredConstructor();
        tokenCtor.setAccessible(true);
        assertThat(tokenCtor.newInstance()).isNotNull();

        var cutoffCtor = RevokedSubjectCutoff.class.getDeclaredConstructor();
        cutoffCtor.setAccessible(true);
        assertThat(cutoffCtor.newInstance()).isNotNull();
    }
}
