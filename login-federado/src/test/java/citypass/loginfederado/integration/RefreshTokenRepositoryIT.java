package citypass.loginfederado.integration;

import citypass.loginfederado.model.RefreshToken;
import citypass.loginfederado.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
class RefreshTokenRepositoryIT {
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

    @Autowired RefreshTokenRepository repository;

    @Test
    void persistsAndFindsByHash() {
        var token = token("hash-1", UUID.randomUUID());
        repository.saveAndFlush(token);
        assertThat(repository.findByTokenHash("hash-1")).contains(token);
    }

    @Test
    void revokesWholeChainButKeepsAlreadyRevokedUntouched() {
        UUID chain = UUID.randomUUID();
        var alive = token("alive", chain);
        var other = token("other", chain);
        other.revoke(Instant.now().minusSeconds(10));
        repository.saveAllAndFlush(java.util.List.of(alive, other));

        int affected = repository.revokeChain(chain, Instant.now());
        assertThat(affected).isEqualTo(1);
        assertThat(repository.findByTokenHash("alive").orElseThrow().isRevoked()).isTrue();
        assertThat(repository.findByTokenHash("other").orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test
    void revokesAllActiveTokensForSubject() {
        var a = token("a", UUID.randomUUID());
        var b = token("b", UUID.randomUUID());
        var c = token("c", UUID.randomUUID());
        c.revoke(Instant.now().minusSeconds(1));
        repository.saveAllAndFlush(java.util.List.of(a, b, c));

        int affected = repository.revokeAllForSub("U000042", Instant.now());
        assertThat(affected).isEqualTo(2);
        assertThat(repository.findByTokenHash("a").orElseThrow().isRevoked()).isTrue();
        assertThat(repository.findByTokenHash("b").orElseThrow().isRevoked()).isTrue();
    }

    private RefreshToken token(String hash, UUID chain) {
        return new RefreshToken("U000042", chain, "client", "aud", hash,
                Instant.now(), Instant.now().plusSeconds(3600));
    }
}
