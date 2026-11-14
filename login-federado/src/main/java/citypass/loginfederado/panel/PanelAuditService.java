package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.PaginatedResponse;
import citypass.loginfederado.panel.dto.PanelAuditSearchCriteria;
import citypass.loginfederado.panel.dto.PanelAuditView;
import citypass.loginfederado.panel.model.PanelAuditEntry;
import citypass.loginfederado.panel.repository.PanelAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persistencia de auditoría del panel. Falla de auditoría = falla de la
 * operación: si no se puede registrar, la mutación no debería haber pasado.
 * (Transaccional junto a la operación LDAP cuando el llamador lo envuelve.)
 */
@Service
public class PanelAuditService {

    private static final Logger log = LoggerFactory.getLogger(PanelAuditService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final PanelAuditRepository repository;

    public PanelAuditService(PanelAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(PanelAuthorization.Delegate actor, String action, String target, String detail) {
        try {
            repository.save(new PanelAuditEntry(
                    actor.sub(), actor.uid(), actor.module(), action, target, detail));
        } catch (Exception ex) {
            // No bloqueamos la operación por una falla de auditoría, pero queda
            // marcado en logs para revisión — es una anomalía operativa seria.
            log.error("No se pudo persistir auditoría del panel: actor={} action={} target={}",
                    actor.sub(), action, target, ex);
        }
    }

    /** Consulta paginada; filtros, orden y conteo se resuelven en la base. */
    @Transactional(readOnly = true)
    public PaginatedResponse<PanelAuditView> search(PanelAuditSearchCriteria criteria) {
        validate(criteria);

        String actor = normalize(criteria.actor());
        String action = normalize(criteria.action());
        String module = normalizeModule(criteria.module());
        var pageable = PageRequest.of(
                criteria.page(),
                criteria.size(),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))
        );
        var result = repository.findAll(specification(criteria, actor, action, module), pageable);
        List<PanelAuditView> content = result.getContent().stream()
                .map(PanelAuditService::toView)
                .toList();

        return new PaginatedResponse<>(
                content,
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }

    private static Specification<PanelAuditEntry> specification(
            PanelAuditSearchCriteria criteria,
            String actor,
            String action,
            String module
    ) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actor != null) {
                String loweredActor = actor.toLowerCase(Locale.ROOT);
                String uidPattern = "%" + escapeLike(loweredActor) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("actorUid")), uidPattern, '\\'),
                        builder.equal(builder.lower(root.get("actorSub")), loweredActor)
                ));
            }
            if (action != null) {
                predicates.add(builder.equal(
                        builder.lower(root.get("action")), action.toLowerCase(Locale.ROOT)));
            }
            if (criteria.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), criteria.from()));
            }
            if (criteria.to() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("occurredAt"), criteria.to()));
            }
            if (module != null) {
                predicates.add(builder.equal(root.get("module"), module));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void validate(PanelAuditSearchCriteria criteria) {
        if (criteria == null) {
            throw badRequest("Los criterios de auditoría son obligatorios");
        }
        if (criteria.page() < 0) {
            throw badRequest("La página no puede ser negativa");
        }
        if (criteria.size() < 1 || criteria.size() > MAX_PAGE_SIZE) {
            throw badRequest("El tamaño de página debe estar entre 1 y 100");
        }
        if (criteria.from() != null && criteria.to() != null && criteria.from().isAfter(criteria.to())) {
            throw badRequest("El instante 'from' no puede ser posterior a 'to'");
        }
        String module = normalizeModule(criteria.module());
        if (module != null && !PanelDirectoryRules.MODULES.contains(module)) {
            throw badRequest("Módulo inválido: " + criteria.module());
        }
    }

    private static String normalizeModule(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static PanelAuditView toView(PanelAuditEntry entry) {
        return new PanelAuditView(
                entry.getId(),
                entry.getActorSub(),
                entry.getActorUid(),
                entry.getModule(),
                entry.getAction(),
                entry.getTarget(),
                entry.getDetail(),
                entry.getOccurredAt()
        );
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
