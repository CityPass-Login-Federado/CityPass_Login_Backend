package citypass.loginfederado.integration;

import citypass.loginfederado.model.AnomalyBlockedEvent;
import citypass.loginfederado.model.AnomalyEventStatus;
import citypass.loginfederado.repository.AnomalyBlockedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private static final Instant BASE_TIME = Instant.parse("2026-11-16T12:00:00Z");

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
    void nullUsernameReturnsResultsWithoutPostgresTypeError() {
        AnomalyBlockedEvent event = save("jperez", "reclamos", BASE_TIME);

        Page<AnomalyBlockedEvent> result = search(
                "reclamos", AnomalyEventStatus.BLOCKED, null, 0, 20);

        assertThat(result).containsExactly(event);
    }

    @Test
    void partialUsernameIsCaseInsensitive() {
        AnomalyBlockedEvent event = save("Juan.Perez", "reclamos", BASE_TIME);
        save("mlopez", "reclamos", BASE_TIME.minusSeconds(60));

        Page<AnomalyBlockedEvent> result = search(
                "reclamos", AnomalyEventStatus.BLOCKED, "pErEz", 0, 20);

        assertThat(result).containsExactly(event);
    }

    @Test
    void unknownUsernameReturnsAnEmptyPage() {
        save("jperez", "reclamos", BASE_TIME);

        Page<AnomalyBlockedEvent> result = search(
                "reclamos", AnomalyEventStatus.BLOCKED, "inexistente", 0, 20);

        assertThat(result).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void nullModuleProvidesTheGlobalView() {
        AnomalyBlockedEvent reclamos = save("jperez", "reclamos", BASE_TIME);
        AnomalyBlockedEvent movilidad = save(
                "mlopez", "movilidad", BASE_TIME.minusSeconds(60));

        Page<AnomalyBlockedEvent> result = search(
                null, AnomalyEventStatus.BLOCKED, null, 0, 20);

        assertThat(result).containsExactly(reclamos, movilidad);
    }

    @Test
    void moduleLimitsResultsToTheDelegatesScope() {
        AnomalyBlockedEvent reclamos = save("jperez", "reclamos", BASE_TIME);
        save("mlopez", "movilidad", BASE_TIME.plusSeconds(60));

        Page<AnomalyBlockedEvent> result = search(
                "reclamos", AnomalyEventStatus.BLOCKED, null, 0, 20);

        assertThat(result).containsExactly(reclamos);
    }

    @Test
    void statusFiltersResults() {
        AnomalyBlockedEvent blocked = save("jperez", "reclamos", BASE_TIME);
        AnomalyBlockedEvent approved = save(
                "mlopez", "reclamos", BASE_TIME.minusSeconds(60));
        approved.approve(
                "U000001", "delegado", BASE_TIME, BASE_TIME.plusSeconds(900));
        anomalyEvents.flush();

        assertThat(search(null, AnomalyEventStatus.BLOCKED, null, 0, 20))
                .containsExactly(blocked);
        assertThat(search(null, AnomalyEventStatus.APPROVED, null, 0, 20))
                .containsExactly(approved);
    }

    @Test
    void preservesPaginationAndDescendingBlockedAtOrder() {
        AnomalyBlockedEvent oldest = save(
                "oldest", "reclamos", BASE_TIME.minusSeconds(120));
        AnomalyBlockedEvent newest = save(
                "newest", "reclamos", BASE_TIME);
        AnomalyBlockedEvent middle = save(
                "middle", "reclamos", BASE_TIME.minusSeconds(60));

        Page<AnomalyBlockedEvent> firstPage = search(
                "reclamos", AnomalyEventStatus.BLOCKED, null, 0, 2);
        Page<AnomalyBlockedEvent> secondPage = search(
                "reclamos", AnomalyEventStatus.BLOCKED, null, 1, 2);

        assertThat(firstPage).containsExactly(newest, middle);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getNumber()).isZero();
        assertThat(firstPage.getSize()).isEqualTo(2);
        assertThat(secondPage).containsExactly(oldest);
        assertThat(secondPage.getNumber()).isEqualTo(1);
    }

    @Test
    void searchesAndLocksOnlyTheMatchingConsumableApproval() {
        Instant now = Instant.now();
        AnomalyBlockedEvent event = new AnomalyBlockedEvent(
                "U000042", "jperez", "reclamos", "citypass-reclamos-web",
                "10.0.0.1", "Browser", 0.91, "[\"IP nueva\"]", now.minusSeconds(60));
        event.approve("U000001", "delegado", now, now.plusSeconds(900));
        anomalyEvents.saveAndFlush(event);

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

    private AnomalyBlockedEvent save(String username, String module, Instant blockedAt) {
        AnomalyBlockedEvent event = new AnomalyBlockedEvent(
                "U000042", username, module, "citypass-admin-web",
                "10.0.0.1", "Browser", 0.91, "[\"IP nueva\"]", blockedAt);
        return anomalyEvents.saveAndFlush(event);
    }

    private Page<AnomalyBlockedEvent> search(
            String module,
            AnomalyEventStatus status,
            String username,
            int page,
            int size
    ) {
        PageRequest pageable = PageRequest.of(
                page, size, Sort.by(Sort.Direction.DESC, "blockedAt"));
        return anomalyEvents.search(module, status, username, pageable);
    }
}
