package citypass.loginfederado.service;

import citypass.loginfederado.dto.AnomalyScoreResponse;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.model.AnomalyBlockedEvent;
import citypass.loginfederado.model.AnomalyEventStatus;
import citypass.loginfederado.panel.PanelAuditService;
import citypass.loginfederado.panel.PanelAuthorization;
import citypass.loginfederado.panel.dto.AnomalyEventView;
import citypass.loginfederado.panel.dto.PaginatedResponse;
import citypass.loginfederado.repository.AnomalyBlockedEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AnomalyBlockedEventService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final TypeReference<List<String>> REASONS_TYPE = new TypeReference<>() { };

    private final AnomalyBlockedEventRepository repository;
    private final LoginAttemptService loginAttemptService;
    private final PanelAuditService auditService;
    private final ObjectMapper objectMapper;
    private final long approvalTtlMinutes;

    public AnomalyBlockedEventService(
            AnomalyBlockedEventRepository repository,
            LoginAttemptService loginAttemptService,
            PanelAuditService auditService,
            ObjectMapper objectMapper,
            @Value("${anomaly.approval.ttl-minutes:15}") long approvalTtlMinutes
    ) {
        if (approvalTtlMinutes <= 0) {
            throw new IllegalArgumentException("La vigencia de aprobación debe ser positiva");
        }
        this.repository = repository;
        this.loginAttemptService = loginAttemptService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.approvalTtlMinutes = approvalTtlMinutes;
    }

    @Transactional
    public UUID recordBlockedEvent(
            LdapDirectoryPerson person,
            String clientId,
            String ipAddress,
            String userAgent,
            AnomalyScoreResponse assessment
    ) {
        AnomalyBlockedEvent event = new AnomalyBlockedEvent(
                person.sub(),
                normalizeUsername(person.uid()),
                person.module().toLowerCase(),
                clientId,
                normalizeContext(ipAddress),
                normalizeContext(userAgent),
                assessment.riskScore(),
                serializeReasons(assessment.reasons()),
                Instant.now()
        );
        return repository.save(event).getId();
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<AnomalyEventView> listEvents(
            String module,
            AnomalyEventStatus status,
            String username,
            int page,
            int size
    ) {
        validatePage(page, size);
        String normalizedModule = module == null ? null : module.toLowerCase();
        String normalizedUsername = normalizeSearch(username);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "blockedAt"));
        var result = repository.search(normalizedModule, status, normalizedUsername, pageable);
        List<AnomalyEventView> content = result.getContent().stream()
                .map(this::toView)
                .toList();
        return new PaginatedResponse<>(
                content,
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }

    @Transactional
    public AnomalyEventView approveEvent(UUID eventId, PanelAuthorization.Delegate actor) {
        Instant now = Instant.now();
        AnomalyBlockedEvent event = repository.findByIdForUpdate(eventId)
                .orElseThrow(() -> notFound());
        requireEventScope(event, actor);

        boolean changed = event.approve(
                actor.sub(),
                actor.uid(),
                now,
                now.plus(approvalTtlMinutes, ChronoUnit.MINUTES)
        );
        if (changed) {
            loginAttemptService.clearLockout(event.getUsername());
            PanelAuthorization.Delegate scopedActor = actor.global()
                    ? new PanelAuthorization.Delegate(
                            actor.sub(), actor.uid(), event.getModule(), true)
                    : actor;
            auditService.record(
                    scopedActor,
                    "ANOMALY_EVENT_APPROVED",
                    event.getId().toString(),
                    "username=" + event.getUsername()
                            + " riskScore=" + event.getRiskScore()
                            + " expiresAt=" + event.getApprovalExpiresAt()
            );
        }
        return toView(event);
    }

    @Transactional
    public boolean consumeApprovedOverride(
            String username,
            String clientId,
            String ipAddress,
            String userAgent
    ) {
        Instant now = Instant.now();
        List<AnomalyBlockedEvent> approvals = repository.findConsumableApproval(
                normalizeUsername(username),
                clientId,
                normalizeContext(ipAddress),
                normalizeContext(userAgent),
                now,
                PageRequest.of(0, 1)
        );
        if (approvals.isEmpty()) {
            return false;
        }
        approvals.getFirst().consume(now);
        return true;
    }

    private void requireEventScope(
            AnomalyBlockedEvent event,
            PanelAuthorization.Delegate actor
    ) {
        if (!actor.global() && !event.getModule().equalsIgnoreCase(actor.module())) {
            throw notFound();
        }
    }

    private AnomalyEventView toView(AnomalyBlockedEvent event) {
        return new AnomalyEventView(
                event.getId(),
                event.getUserSub(),
                event.getUsername(),
                event.getModule(),
                event.getClientId(),
                event.getIpAddress(),
                event.getUserAgent(),
                event.getRiskScore(),
                deserializeReasons(event.getReasons()),
                event.getStatus(),
                event.getBlockedAt(),
                event.getApprovedAt(),
                event.getApprovalExpiresAt(),
                event.getApprovedBySub(),
                event.getApprovedByUid(),
                event.getConsumedAt()
        );
    }

    private String serializeReasons(List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons == null ? List.of() : reasons);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudieron serializar las razones de anomalía", ex);
        }
    }

    private List<String> deserializeReasons(String reasons) {
        try {
            return objectMapper.readValue(reasons, REASONS_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudieron leer las razones de anomalía", ex);
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "La página debe ser no negativa y el tamaño debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
    }

    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase();
    }

    private static String normalizeSearch(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeContext(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(NOT_FOUND, "Evento de anomalía inexistente");
    }
}

