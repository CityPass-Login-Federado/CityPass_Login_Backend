package citypass.loginfederado.integration;

import citypass.loginfederado.model.PasswordResetToken;
import citypass.loginfederado.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
class PasswordResetTokenRepositoryIT {
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

    @Autowired PasswordResetTokenRepository repository;

    private PasswordResetToken token(String hash, String sub, long ttlSeconds) {
        Instant now = Instant.now();
        return new PasswordResetToken(sub, "jperez", hash, now, now.plusSeconds(ttlSeconds));
    }

    @Test
    void persistsAndFindsByHash() {
        repository.saveAndFlush(token("hash-1", "U000042", 1800));

        assertThat(repository.findByTokenHash("hash-1")).isPresent();
        assertThat(repository.findByTokenHash("inexistente")).isEmpty();
    }

    @Test
    void deleteBySubRemovesEveryTokenOfTheAccount() {
        repository.save(token("viejo-1", "U000042", 1800));
        repository.save(token("viejo-2", "U000042", 1800));
        repository.saveAndFlush(token("otro", "U000043", 1800));

        assertThat(repository.deleteBySub("U000042")).isEqualTo(2);
        assertThat(repository.findByTokenHash("viejo-1")).isEmpty();
        assertThat(repository.findByTokenHash("viejo-2")).isEmpty();
        assertThat(repository.findByTokenHash("otro")).isPresent();
    }

    @Test
    void consumeIsAtomicAndSingleUse() {
        repository.saveAndFlush(token("canje", "U000042", 1800));
        Instant now = Instant.now();

        assertThat(repository.markUsedIfActive("canje", now)).isEqualTo(1);
        assertThat(repository.markUsedIfActive("canje", now)).isZero();
        assertThat(repository.findByTokenHash("canje").orElseThrow().isUsable()).isFalse();
    }

    @Test
    void expiredTokenCannotBeConsumed() {
        repository.saveAndFlush(token("vencido", "U000042", -60));

        assertThat(repository.markUsedIfActive("vencido", Instant.now())).isZero();
        assertThat(repository.findByTokenHash("vencido").orElseThrow().isUsable()).isFalse();
    }
}
