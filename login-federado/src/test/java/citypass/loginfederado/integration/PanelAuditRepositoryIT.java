package citypass.loginfederado.integration;

import citypass.loginfederado.panel.PanelAuditService;
import citypass.loginfederado.panel.dto.PanelAuditSearchCriteria;
import citypass.loginfederado.panel.repository.PanelAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always"
})
@Import(PanelAuditService.class)
class PanelAuditRepositoryIT {

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
    PanelAuditService audit;

    @Autowired
    PanelAuditRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearAudit() {
        repository.deleteAllInBatch();
    }

    @Test
    void filtersActorUidActorSubAndActionCaseInsensitively() {
        Instant occurredAt = Instant.parse("2026-11-14T12:00:00Z");
        UUID firstId = id(1);
        UUID secondId = id(2);
        insert(firstId, "U000001", "Juan.Perez", "reclamos", "PERSON_CREATED", occurredAt);
        insert(secondId, "U000002", "mlopez", "movilidad", "GROUP_CREATED", occurredAt);

        var byPartialUid = audit.search(criteria("pErEz", "person_created", null, null, null, 0, 20));
        var byStableSub = audit.search(criteria("u000002", null, null, null, null, 0, 20));

        assertThat(byPartialUid.content()).extracting(view -> view.id()).containsExactly(firstId);
        assertThat(byStableSub.content()).extracting(view -> view.id()).containsExactly(secondId);
    }

    @Test
    void combinesAllFiltersAndIncludesBothDateBoundaries() {
        Instant from = Instant.parse("2026-11-14T10:00:00Z");
        Instant to = Instant.parse("2026-11-14T12:00:00Z");
        UUID atFrom = id(10);
        UUID atTo = id(11);
        insert(atFrom, "U000001", "jperez", "reclamos", "PERSON_UPDATED", from);
        insert(id(12), "U000001", "jperez", "reclamos", "PERSON_UPDATED", from.minusSeconds(1));
        insert(atTo, "U000001", "jperez", "reclamos", "PERSON_UPDATED", to);
        insert(id(13), "U000001", "jperez", "reclamos", "PERSON_UPDATED", to.plusSeconds(1));
        insert(id(14), "U000001", "jperez", "movilidad", "PERSON_UPDATED", from.plusSeconds(30));
        insert(id(15), "U000001", "jperez", "reclamos", "GROUP_CREATED", from.plusSeconds(30));

        var response = audit.search(criteria(
                "jpere", "person_updated", from, to, "reclamos", 0, 20));

        assertThat(response.content()).extracting(view -> view.id()).containsExactly(atTo, atFrom);
        assertThat(response.totalElements()).isEqualTo(2);
    }

    @Test
    void ordersByDateThenIdDescendingAndReportsPaginationTotals() {
        Instant sameTime = Instant.parse("2026-11-14T12:00:00Z");
        UUID first = id(21);
        UUID second = id(22);
        UUID third = id(23);
        insert(first, "U000001", "jperez", "reclamos", "A", sameTime);
        insert(second, "U000001", "jperez", "reclamos", "B", sameTime);
        insert(third, "U000001", "jperez", "reclamos", "C", sameTime);

        var pageZero = audit.search(criteria(null, null, null, null, "reclamos", 0, 2));
        var pageOne = audit.search(criteria(null, null, null, null, "reclamos", 1, 2));

        assertThat(pageZero.content()).extracting(view -> view.id()).containsExactly(third, second);
        assertThat(pageZero.totalElements()).isEqualTo(3);
        assertThat(pageZero.totalPages()).isEqualTo(2);
        assertThat(pageZero.currentPage()).isZero();
        assertThat(pageZero.size()).isEqualTo(2);
        assertThat(pageOne.content()).extracting(view -> view.id()).containsExactly(first);
        assertThat(pageOne.currentPage()).isEqualTo(1);
    }

    private PanelAuditSearchCriteria criteria(
            String actor,
            String action,
            Instant from,
            Instant to,
            String module,
            int page,
            int size
    ) {
        return new PanelAuditSearchCriteria(actor, action, from, to, module, page, size);
    }

    private void insert(
            UUID id,
            String actorSub,
            String actorUid,
            String module,
            String action,
            Instant occurredAt
    ) {
        jdbc.update(
                "INSERT INTO panel_audit "
                        + "(id, actor_sub, actor_uid, module, action, target, detail, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                actorSub,
                actorUid,
                module,
                action,
                "target-" + id,
                "detail-" + id,
                Timestamp.from(occurredAt)
        );
    }

    private static UUID id(long suffix) {
        return new UUID(0, suffix);
    }
}
