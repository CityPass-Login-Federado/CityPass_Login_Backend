package citypass.loginfederado.service;

import citypass.loginfederado.dto.AnomalyScoreResponse;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.model.AnomalyBlockedEvent;
import citypass.loginfederado.model.AnomalyEventStatus;
import citypass.loginfederado.panel.PanelAuditService;
import citypass.loginfederado.panel.PanelAuthorization;
import citypass.loginfederado.repository.AnomalyBlockedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnomalyBlockedEventServiceTest {

    private AnomalyBlockedEventRepository repository;
    private LoginAttemptService loginAttempts;
    private PanelAuditService audit;
    private AnomalyBlockedEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(AnomalyBlockedEventRepository.class);
        loginAttempts = mock(LoginAttemptService.class);
        audit = mock(PanelAuditService.class);
        service = new AnomalyBlockedEventService(
                repository, loginAttempts, audit, new ObjectMapper(), 15);
    }

    @Test
    void recordsRiskScoreReasonsAndSecurityContext() {
        UUID id = UUID.randomUUID();
        when(repository.save(any(AnomalyBlockedEvent.class))).thenAnswer(invocation -> {
            AnomalyBlockedEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "id", id);
            return event;
        });
        var person = new LdapDirectoryPerson(
                "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local",
                "U000042",
                "JPerez",
                "Juan Perez",
                "jperez@citypass.local",
                "RECLAMOS",
                List.of()
        );

        UUID result = service.recordBlockedEvent(
                person,
                "citypass-reclamos-web",
                " 10.0.0.1 ",
                " Browser ",
                new AnomalyScoreResponse(0.91, "BLOCK", List.of("IP nueva"))
        );

        ArgumentCaptor<AnomalyBlockedEvent> captor =
                ArgumentCaptor.forClass(AnomalyBlockedEvent.class);
        verify(repository).save(captor.capture());
        AnomalyBlockedEvent event = captor.getValue();
        assertThat(result).isEqualTo(id);
        assertThat(event.getUsername()).isEqualTo("jperez");
        assertThat(event.getModule()).isEqualTo("reclamos");
        assertThat(event.getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(event.getRiskScore()).isEqualTo(0.91);
        assertThat(event.getReasons()).isEqualTo("[\"IP nueva\"]");
        assertThat(event.getStatus()).isEqualTo(AnomalyEventStatus.BLOCKED);
    }

    @Test
    void listsAndMapsEventsAsPaginatedViews() {
        AnomalyBlockedEvent event = blockedEvent();
        when(repository.search(eq("reclamos"), eq(AnomalyEventStatus.BLOCKED),
                eq("jperez"), any()))
                .thenReturn(new PageImpl<>(List.of(event)));

        var result = service.listEvents(
                "RECLAMOS", AnomalyEventStatus.BLOCKED, " jperez ", 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().riskScore()).isEqualTo(0.91);
        assertThat(result.content().getFirst().reasons()).containsExactly("IP nueva");
    }

    @Test
    void approvalIsIdempotentAndClearsLockoutOnlyOnce() {
        UUID id = UUID.randomUUID();
        AnomalyBlockedEvent event = blockedEvent();
        ReflectionTestUtils.setField(event, "id", id);
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(event));
        var actor = new PanelAuthorization.Delegate("U000001", "delegado", "reclamos");

        var first = service.approveEvent(id, actor);
        var second = service.approveEvent(id, actor);

        assertThat(first.status()).isEqualTo(AnomalyEventStatus.APPROVED);
        assertThat(second.status()).isEqualTo(AnomalyEventStatus.APPROVED);
        verify(loginAttempts, times(1)).clearLockout("jperez");
        verify(audit, times(1)).record(
                eq(actor), eq("ANOMALY_EVENT_APPROVED"), eq(id.toString()), any());
    }

    @Test
    void delegateCannotApproveAnotherModuleEvent() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(blockedEvent()));
        var actor = new PanelAuthorization.Delegate("U000001", "delegado", "movilidad");

        assertThatThrownBy(() -> service.approveEvent(id, actor))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error)
                        .getStatusCode().value()).isEqualTo(404));

        verify(loginAttempts, never()).clearLockout(any());
    }

    @Test
    void consumesMatchingApprovalOnlyOnce() {
        AnomalyBlockedEvent event = blockedEvent();
        event.approve("U000001", "delegado", Instant.now(), Instant.now().plusSeconds(900));
        when(repository.findConsumableApproval(
                eq("jperez"), eq("citypass-reclamos-web"), eq("10.0.0.1"),
                eq("Browser"), any(), any()))
                .thenReturn(List.of(event))
                .thenReturn(List.of());

        assertThat(service.consumeApprovedOverride(
                "JPerez", "citypass-reclamos-web", " 10.0.0.1 ", " Browser "))
                .isTrue();
        assertThat(event.getStatus()).isEqualTo(AnomalyEventStatus.CONSUMED);
        assertThat(service.consumeApprovedOverride(
                "jperez", "citypass-reclamos-web", "10.0.0.1", "Browser"))
                .isFalse();
    }

    private AnomalyBlockedEvent blockedEvent() {
        return new AnomalyBlockedEvent(
                "U000042",
                "jperez",
                "reclamos",
                "citypass-reclamos-web",
                "10.0.0.1",
                "Browser",
                0.91,
                "[\"IP nueva\"]",
                Instant.now()
        );
    }
}

