package citypass.loginfederado.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Denylist de access tokens revocados por su `jti` (claim estándar del JWT).
 *
 * El access token es stateless: sin esto, un logout solo mata el refresh y el
 * access token sigue sirviendo hasta 15 minutos. Guardamos el jti revocado
 * hasta su expiración natural (después ya no sirve para nada y se purga).
 */
@Entity
@Table(name = "revoked_access_tokens")
public class RevokedAccessToken {

    /** jti del JWT: identificador único del token revocado. */
    @Id
    @Column(name = "jti", nullable = false, length = 64)
    private String jti;

    /** employeeNumber del dueño (para poder revocar todas sus sesiones). */
    @Column(name = "sub", length = 16)
    private String sub;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RevokedAccessToken() {
    }

    public RevokedAccessToken(String jti, String sub, Instant expiresAt) {
        this.jti = jti;
        this.sub = sub;
        this.expiresAt = expiresAt;
    }

    public String getJti() { return jti; }
    public String getSub() { return sub; }
    public Instant getExpiresAt() { return expiresAt; }
}
