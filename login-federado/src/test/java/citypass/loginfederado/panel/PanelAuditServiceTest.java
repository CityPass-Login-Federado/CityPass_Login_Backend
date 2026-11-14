package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.PanelAuditSearchCriteria;
import citypass.loginfederado.panel.model.PanelAuditEntry;
import citypass.loginfederado.panel.repository.PanelAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PanelAuditServiceTest {
    @Test
    void persistsAuditEntry() {
        var repo = mock(PanelAuditRepository.class);
        var service = new PanelAuditService(repo);
        var actor = new PanelAuthorization.Delegate("U1", "admin", "reclamos");
        service.record(actor, "GROUP_CREATED", "cn=ops", "detail");
        verify(repo).save(any());
    }

    @Test
    void auditFailureDoesNotBreakBusinessOperation() {
        var repo = mock(PanelAuditRepository.class);
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        var service = new PanelAuditService(repo);
        service.record(new PanelAuthorization.Delegate("U1", "admin", "reclamos"), "X", "target", null);
        verify(repo).save(any());
    }

    @Test
    void searchMapsEntitiesAndUsesStableDescendingOrder() {
        var repo = mock(PanelAuditRepository.class);
        var service = new PanelAuditService(repo);
        PanelAuditEntry entry = new PanelAuditEntry(
                "U000001", "jperez", "reclamos", "PERSON_CREATED", "jperez", "alta");
        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(repo.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(entry), invocation.getArgument(1), 1));

        var response = service.search(new PanelAuditSearchCriteria(
                "  jperez  ", " person_created ", null, null, " RECLAMOS ", 0, 20));

        assertThat(response.content()).singleElement().satisfies(view -> {
            assertThat(view.actorSub()).isEqualTo("U000001");
            assertThat(view.actorUid()).isEqualTo("jperez");
            assertThat(view.module()).isEqualTo("reclamos");
            assertThat(view.action()).isEqualTo("PERSON_CREATED");
            assertThat(view.target()).isEqualTo("jperez");
            assertThat(view.detail()).isEqualTo("alta");
        });
        assertThat(response.totalElements()).isEqualTo(1);
        verify(repo).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("occurredAt").isDescending()).isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    void rejectsInvalidRangePageSizeAndModule() {
        var repo = mock(PanelAuditRepository.class);
        var service = new PanelAuditService(repo);
        Instant now = Instant.parse("2026-11-14T12:00:00Z");

        assertBadRequest(() -> service.search(criteria(now, now.minusSeconds(1), null, 0, 20)),
                "from");
        assertBadRequest(() -> service.search(criteria(null, null, null, -1, 20)),
                "página");
        assertBadRequest(() -> service.search(criteria(null, null, null, 0, 0)),
                "entre 1 y 100");
        assertBadRequest(() -> service.search(criteria(null, null, null, 0, 101)),
                "entre 1 y 100");
        assertBadRequest(() -> service.search(criteria(null, null, "desconocido", 0, 20)),
                "Módulo inválido");
        verify(repo, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    private static PanelAuditSearchCriteria criteria(
            Instant from,
            Instant to,
            String module,
            int page,
            int size
    ) {
        return new PanelAuditSearchCriteria(null, null, from, to, module, page, size);
    }

    private static void assertBadRequest(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            String message
    ) {
        assertThatThrownBy(call)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .contains(message));
    }
}
