package citypass.loginfederado.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Corte temporal de revocación por sujeto (`sub`).
 *
 * Complementa la denylist por `jti` cuando se revocan TODAS las sesiones de
 * una persona (cambio/recupero de contraseña o baja en el panel): cualquier
 * access token con `iat <= revokedBefore` se rechaza hasta que expire de
 * forma natural (`expiresAt`), momento en que la fila se purga.
 */
@Entity
@Table(name = "revoked_subject_cutoffs")
public class RevokedSubjectCutoff {

    @Id
    @Column(name = "sub", nullable = false, length = 16)
    private String sub;

    @Column(name = "revoked_before", nullable = false)
    private Instant revokedBefore;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RevokedSubjectCutoff() {
    }

    public RevokedSubjectCutoff(String sub, Instant revokedBefore, Instant expiresAt) {
        this.sub = sub;
        this.revokedBefore = revokedBefore;
        this.expiresAt = expiresAt;
    }

    public void advanceCutoff(Instant revokedBefore, Instant expiresAt) {
        this.revokedBefore = revokedBefore;
        this.expiresAt = expiresAt;
    }

    public String getSub() { return sub; }
    public Instant getRevokedBefore() { return revokedBefore; }
    public Instant getExpiresAt() { return expiresAt; }
}
