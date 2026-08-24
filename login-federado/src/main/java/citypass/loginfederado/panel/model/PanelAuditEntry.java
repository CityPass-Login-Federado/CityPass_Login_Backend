package citypass.loginfederado.panel.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de auditoría del panel: cada mutación queda atada al delegado que
 * la hizo (sub estable, no uid). El manual se lo promete a los delegados:
 * "las altas, bajas y cambios de grupos quedan registrados".
 */
@Entity
@Table(name = "panel_audit")
public class PanelAuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "actor_sub", nullable = false, length = 16)
    private String actorSub;

    @Column(name = "actor_uid", nullable = false)
    private String actorUid;

    @Column(nullable = false, length = 64)
    private String module;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(nullable = false)
    private String target;

    @Column(length = 1024)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PanelAuditEntry() {
    }

    public PanelAuditEntry(String actorSub, String actorUid, String module,
                           String action, String target, String detail) {
        this.actorSub = actorSub;
        this.actorUid = actorUid;
        this.module = module;
        this.action = action;
        this.target = target;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getActorSub() { return actorSub; }
    public String getActorUid() { return actorUid; }
    public String getModule() { return module; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}
