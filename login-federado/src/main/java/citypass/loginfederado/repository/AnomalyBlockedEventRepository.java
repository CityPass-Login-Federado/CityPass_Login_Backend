package citypass.loginfederado.repository;

import citypass.loginfederado.model.AnomalyBlockedEvent;
import citypass.loginfederado.model.AnomalyEventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnomalyBlockedEventRepository
        extends JpaRepository<AnomalyBlockedEvent, UUID> {

    @Query("""
            SELECT event
            FROM AnomalyBlockedEvent event
            WHERE (:module IS NULL OR event.module = :module)
              AND (:status IS NULL OR event.status = :status)
              AND (:username IS NULL
                   OR LOWER(event.username) LIKE LOWER(CONCAT('%', :username, '%')))
            """)
    Page<AnomalyBlockedEvent> search(
            @Param("module") String module,
            @Param("status") AnomalyEventStatus status,
            @Param("username") String username,
            Pageable pageable
    );

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

