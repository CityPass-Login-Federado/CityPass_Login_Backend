package citypass.loginfederado.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import citypass.loginfederado.model.LoginAttempt;
import citypass.loginfederado.model.RefreshToken;
import citypass.loginfederado.repository.LoginAttemptRepository;
import citypass.loginfederado.repository.RefreshTokenRepository;
import citypass.loginfederado.repository.SessionSpanProjection;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
class MetricsRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("login_federado")
            .withUsername("citypass")
            .withPassword("citypass");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired LoginAttemptRepository loginAttempts;
    @Autowired RefreshTokenRepository refreshTokens;

    @BeforeEach
    void cleanDatabase() {
        refreshTokens.deleteAll();
        loginAttempts.deleteAll();
    }

    @Test
    void countsDistinctSuccessfulUsersAndExcludesFailuresAndTheUpperBoundary() {
        Instant from = Instant.parse("2026-01-15T00:00:00Z");
        Instant to = Instant.parse("2026-01-16T00:00:00Z");
        loginAttempts.saveAllAndFlush(List.of(
                attempt("alice", true, from),
                attempt("alice", true, from.plusSeconds(3600)),
                attempt("bob", false, from.plusSeconds(7200)),
                attempt("carol", true, to),
                attempt("dave", true, from.minusSeconds(1))
        ));

        assertThat(loginAttempts.countDistinctActiveUsersBetween(from, to)).isEqualTo(1);
    }

    @Test
    void groupsOnlySuccessfulLoginsByUtcDatabaseHour() {
        Instant from = Instant.parse("2026-01-15T00:00:00Z");
        Instant to = Instant.parse("2026-01-16T00:00:00Z");
        loginAttempts.saveAllAndFlush(List.of(
                attempt("alice", true, from.plusSeconds(2 * 3600L)),
                attempt("bob", true, from.plusSeconds(2 * 3600L + 30)),
                attempt("carol", false, from.plusSeconds(2 * 3600L + 45)),
                attempt("dave", true, from.plusSeconds(23 * 3600L + 1)),
                attempt("erin", true, to)
        ));

        assertThat(loginAttempts.countSuccessfulLoginsByHourBetween(from, to))
                .extracting(row -> row.getHourOfDay(), row -> row.getLoginCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, 2L),
                        org.assertj.core.groups.Tuple.tuple(23, 1L));
    }

    @Test
    void returnsClosedSessionsOnceAndExcludesActiveSessions() {
        Instant from = Instant.parse("2026-01-15T00:00:00Z");
        Instant to = Instant.parse("2026-01-16T00:00:00Z");

        UUID closedChain = UUID.randomUUID();
        RefreshToken first = token("closed-1", closedChain,
                from.minusSeconds(3600), from.plusSeconds(3600));
        RefreshToken last = token("closed-2", closedChain,
                from.plusSeconds(60), from.plusSeconds(7200));
        last.revoke(from.plusSeconds(7200));

        UUID activeChain = UUID.randomUUID();
        RefreshToken active = token("active", activeChain,
                from.plusSeconds(120), Instant.parse("2099-01-01T00:00:00Z"));

        UUID expiredChain = UUID.randomUUID();
        RefreshToken expired = token("expired", expiredChain,
                from.plusSeconds(180), Instant.parse("2020-01-01T00:00:00Z"));

        refreshTokens.saveAllAndFlush(List.of(first, last, active, expired));

        List<SessionSpanProjection> sessions =
                refreshTokens.findClosedSessionSpansEndedBetween(from, to);

        assertThat(sessions).hasSize(2);
        assertThat(sessions).extracting(SessionSpanProjection::getChainId)
                .containsExactlyInAnyOrder(closedChain, expiredChain);
        SessionSpanProjection closed = sessions.stream()
                .filter(session -> session.getChainId().equals(closedChain))
                .findFirst()
                .orElseThrow();
        assertThat(closed.getStartedAt()).isEqualTo(from.minusSeconds(3600));
        assertThat(closed.getEndedAt()).isEqualTo(from.plusSeconds(7200));
        assertThat(closed.isStillActive()).isFalse();
    }

    @Test
    void excludesClosedSessionEndingExactlyAtUpperBoundary() {
        Instant from = Instant.parse("2026-01-15T00:00:00Z");
        Instant to = Instant.parse("2026-01-16T00:00:00Z");
        UUID chain = UUID.randomUUID();
        RefreshToken token = token("boundary", chain, from, to.plusSeconds(1));
        token.revoke(to);
        refreshTokens.saveAndFlush(token);

        assertThat(refreshTokens.findClosedSessionSpansEndedBetween(from, to)).isEmpty();
    }

    private LoginAttempt attempt(String username, boolean successful, Instant at) {
        return new LoginAttempt(username, "127.0.0.1", "test-agent", successful, at);
    }

    private RefreshToken token(String hash, UUID chain, Instant issuedAt, Instant expiresAt) {
        return new RefreshToken("U000042", chain, "client", "aud", hash, issuedAt, expiresAt);
    }
}
