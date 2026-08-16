package ar.edu.uade.citypass.loginfederado.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String username;

    // Hash SHA-256 del token, nunca el valor crudo.
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    // Roles al momento de la emisión, para reconstruir el access token sin
    // volver a golpear LDAP en cada refresh (se recalculan en el próximo login).
    @Column(name = "roles", nullable = false)
    private String roles;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    protected RefreshToken() {
    }

    public RefreshToken(String username, String tokenHash, List<String> roles, Instant issuedAt, Instant expiresAt) {
        this.username = username;
        this.tokenHash = tokenHash;
        this.roles = String.join(",", roles);
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getTokenHash() { return tokenHash; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }

    public List<String> getRolesList() {
        if (roles == null || roles.isBlank()) return List.of();
        return Arrays.stream(roles.split(",")).collect(Collectors.toList());
    }

    public void revoke() { this.revoked = true; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isValid() { return !revoked && !isExpired(); }
}