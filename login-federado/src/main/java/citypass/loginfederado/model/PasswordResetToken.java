package citypass.loginfederado.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Token de recupero de contraseña de UN SOLO USO (flujo
 * POST /auth/forgot-password → POST /auth/reset-password).
 *
 * - Se persiste SOLO el hash SHA-256; el valor crudo viaja UNA vez en el
 *   enlace del mail y jamás toca la base (misma regla que los refresh
 *   tokens opacos).
 * - LDAP NO se escribe al pedirlo: la contraseña cambia recién al canjear.
 *   Si el mail falla, la credencial vigente sigue intacta.
 * - Una sola fila activa por cuenta: pedir otro token invalida el anterior
 *   (borrado en la misma transacción que inserta el nuevo).
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** employeeNumber dueño del token (el sub que se revalidará al canjear). */
    @Column(nullable = false, length = 16)
    private String sub;

    @Column(nullable = false)
    private String uid;

    // Hash SHA-256 del token, nunca el valor crudo.
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null mientras está activo; instantáneo de canje cuando se usa. */
    @Column(name = "used_at")
    private Instant usedAt;

    protected PasswordResetToken() {
    }

    public PasswordResetToken(String sub, String uid, String tokenHash,
                              Instant requestedAt, Instant expiresAt) {
        this.sub = sub;
        this.uid = uid;
        this.tokenHash = tokenHash;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
    }

    public void markUsed(Instant when) {
        if (this.usedAt == null) {
            this.usedAt = when;
        }
    }

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isUsed() { return usedAt != null; }
    public boolean isUsable() { return !isUsed() && !isExpired(); }

    public UUID getId() { return id; }
    public String getSub() { return sub; }
    public String getUid() { return uid; }
    public String getTokenHash() { return tokenHash; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
}
