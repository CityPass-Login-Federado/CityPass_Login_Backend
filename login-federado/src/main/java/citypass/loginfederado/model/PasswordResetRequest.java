package citypass.loginfederado.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Registro de solicitudes de recupero ACEPTADAS (una fila por emisión real
 * de token). Lo usa el limitador para el cooldown por cuenta y los topes
 * por cuenta e IP. Las solicitudes RECHAZADAS por el limitador no se
 * guardan: hacia afuera todas responden 204 igual.
 */
@Entity
@Table(name = "password_reset_requests")
public class PasswordResetRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** UID normalizado (trim + minúsculas): la misma cuenta siempre igual. */
    @Column(nullable = false)
    private String uid;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    protected PasswordResetRequest() {
    }

    public PasswordResetRequest(String uid, String ipAddress, Instant requestedAt) {
        this.uid = uid;
        this.ipAddress = ipAddress;
        this.requestedAt = requestedAt;
    }

    public UUID getId() { return id; }
    public String getUid() { return uid; }
    public String getIpAddress() { return ipAddress; }
    public Instant getRequestedAt() { return requestedAt; }
}
