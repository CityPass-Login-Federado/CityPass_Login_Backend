package citypass.loginfederado.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "anomaly_blocked_events")
public class AnomalyBlockedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_sub", nullable = false, length = 16)
    private String userSub;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false, length = 64)
    private String module;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "risk_score", nullable = false)
    private double riskScore;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reasons;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AnomalyEventStatus status;

    @Column(name = "blocked_at", nullable = false)
    private Instant blockedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_expires_at")
    private Instant approvalExpiresAt;

    @Column(name = "approved_by_sub", length = 16)
    private String approvedBySub;

    @Column(name = "approved_by_uid")
    private String approvedByUid;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected AnomalyBlockedEvent() {
    }

    public AnomalyBlockedEvent(
            String userSub,
            String username,
            String module,
            String clientId,
            String ipAddress,
            String userAgent,
            double riskScore,
            String reasons,
            Instant blockedAt
    ) {
        this.userSub = userSub;
        this.username = username;
        this.module = module;
        this.clientId = clientId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.riskScore = riskScore;
        this.reasons = reasons;
        this.blockedAt = blockedAt;
        this.status = AnomalyEventStatus.BLOCKED;
    }

    public boolean approve(String actorSub, String actorUid, Instant now, Instant expiresAt) {
        if (status == AnomalyEventStatus.CONSUMED) {
            throw new IllegalStateException("La aprobación del evento ya fue consumida");
        }
        if (status == AnomalyEventStatus.APPROVED
                && approvalExpiresAt != null
                && approvalExpiresAt.isAfter(now)) {
            return false;
        }

        status = AnomalyEventStatus.APPROVED;
        approvedAt = now;
        approvalExpiresAt = expiresAt;
        approvedBySub = actorSub;
        approvedByUid = actorUid;
        consumedAt = null;
        return true;
    }

    public void consume(Instant now) {
        if (status != AnomalyEventStatus.APPROVED
                || approvalExpiresAt == null
                || !approvalExpiresAt.isAfter(now)) {
            throw new IllegalStateException("La aprobación no está vigente");
        }
        status = AnomalyEventStatus.CONSUMED;
        consumedAt = now;
    }

    public UUID getId() { return id; }
    public String getUserSub() { return userSub; }
    public String getUsername() { return username; }
    public String getModule() { return module; }
    public String getClientId() { return clientId; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public double getRiskScore() { return riskScore; }
    public String getReasons() { return reasons; }
    public AnomalyEventStatus getStatus() { return status; }
    public Instant getBlockedAt() { return blockedAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getApprovalExpiresAt() { return approvalExpiresAt; }
    public String getApprovedBySub() { return approvedBySub; }
    public String getApprovedByUid() { return approvedByUid; }
    public Instant getConsumedAt() { return consumedAt; }
    public long getVersion() { return version; }
}

