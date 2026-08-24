package citypass.loginfederado.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token OPACO (no es un JWT, no contiene nada). Reglas innegociables
 * (spec §4.2 / decisión D9):
 *
 * 1. Solo se persiste su hash SHA-256; el valor crudo se entrega UNA vez.
 * 2. Rota en cada uso: al canjearlo queda revocado (revoked_at) y nace uno
 *    nuevo en la MISMA cadena (chain_id).
 * 3. Reuso = robo: si vuelve a llegar un token ya canjeado, se revoca TODA
 *    la cadena de esa persona y se fuerza login (RFC 9700).
 *
 * No se guardan roles ni email: los grupos SIEMPRE se releen contra LDAP
 * en cada canje. La audience/client_id del login sí viajan, porque el token
 * reemitido debe servir para exactamente la misma API que el original.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** employeeNumber de la persona (el sub de sus access tokens). */
    @Column(nullable = false, length = 16)
    private String sub;

    /** Todos los tokens de una misma sesión comparten cadena. */
    @Column(name = "chain_id", nullable = false)
    private UUID chainId;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(nullable = false)
    private String audience;

    // Hash SHA-256 del token, nunca el valor crudo.
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null mientras está vivo; instantáneo de revocación cuando no. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
    }

    public RefreshToken(String sub, UUID chainId, String clientId, String audience,
                        String tokenHash, Instant issuedAt, Instant expiresAt) {
        this.sub = sub;
        this.chainId = chainId;
        this.clientId = clientId;
        this.audience = audience;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public void revoke(Instant when) {
        if (this.revokedAt == null) {
            this.revokedAt = when;
        }
    }

    public boolean isRevoked() { return revokedAt != null; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isUsable() { return !isRevoked() && !isExpired(); }

    public UUID getId() { return id; }
    public String getSub() { return sub; }
    public UUID getChainId() { return chainId; }
    public String getClientId() { return clientId; }
    public String getAudience() { return audience; }
    public String getTokenHash() { return tokenHash; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
