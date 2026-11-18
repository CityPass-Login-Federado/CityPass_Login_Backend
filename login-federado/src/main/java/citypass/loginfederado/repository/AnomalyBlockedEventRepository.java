package citypass.loginfederado.repository;

import citypass.loginfederado.model.AnomalyBlockedEvent;
import citypass.loginfederado.model.AnomalyEventStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface AnomalyBlockedEventRepository
        extends JpaRepository<AnomalyBlockedEvent, UUID>,
        JpaSpecificationExecutor<AnomalyBlockedEvent> {

    default Page<AnomalyBlockedEvent> search(
            String module,
            AnomalyEventStatus status,
            String username,
            Pageable pageable
    ) {
        Specification<AnomalyBlockedEvent> filters = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (module != null) {
                predicates.add(builder.equal(root.get("module"), module));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (username != null) {
                String pattern = "%" + username.toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.like(builder.lower(root.get("username")), pattern));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return findAll(filters, pageable);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT event FROM AnomalyBlockedEvent event WHERE event.id = :id")
    Optional<AnomalyBlockedEvent> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT event
            FROM AnomalyBlockedEvent event
            WHERE LOWER(event.username) = LOWER(:username)
              AND event.clientId = :clientId
              AND event.status = citypass.loginfederado.model.AnomalyEventStatus.APPROVED
              AND event.approvalExpiresAt > :now
              AND ((:ipAddress IS NULL AND event.ipAddress IS NULL)
                   OR event.ipAddress = :ipAddress)
              AND ((:userAgent IS NULL AND event.userAgent IS NULL)
                   OR event.userAgent = :userAgent)
            ORDER BY event.approvedAt DESC
            """)
    List<AnomalyBlockedEvent> findConsumableApproval(
            @Param("username") String username,
            @Param("clientId") String clientId,
            @Param("ipAddress") String ipAddress,
            @Param("userAgent") String userAgent,
            @Param("now") Instant now,
            Pageable pageable
    );
}

