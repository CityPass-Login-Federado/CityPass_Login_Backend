package citypass.loginfederado.integration;

import citypass.loginfederado.model.AnomalyBlockedEvent;
import citypass.loginfederado.model.AnomalyEventStatus;
import citypass.loginfederado.repository.AnomalyBlockedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always"
})
class AnomalyBlockedEventRepositoryIT {

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

    @Autowired
    AnomalyBlockedEventRepository anomalyEvents;

    @Test
    void searchesAndLocksOnlyTheMatchingConsumableApproval() {
        Instant now = Instant.now();
        AnomalyBlockedEvent event = new AnomalyBlockedEvent(
                "U000042", "jperez", "reclamos", "citypass-reclamos-web",
                "10.0.0.1", "Browser", 0.91, "[\"IP nueva\"]", now.minusSeconds(60));
        event.approve("U000001", "delegado", now, now.plusSeconds(900));
        anomalyEvents.saveAndFlush(event);

        assertThat(anomalyEvents.search(
                "reclamos", AnomalyEventStatus.APPROVED, "perez", PageRequest.of(0, 10)))
                .containsExactly(event);
        assertThat(anomalyEvents.findConsumableApproval(
                "jperez", "citypass-reclamos-web", "10.0.0.1", "Browser", now,
                PageRequest.of(0, 1)))
                .containsExactly(event);
        assertThat(anomalyEvents.findConsumableApproval(
                "jperez", "citypass-reclamos-web", "10.0.0.2", "Browser", now,
                PageRequest.of(0, 1)))
                .isEmpty();
        assertThat(anomalyEvents.findConsumableApproval(
                "jperez", "citypass-admin-web", "10.0.0.1", "Browser", now,
                PageRequest.of(0, 1)))
                .isEmpty();
    }
}
