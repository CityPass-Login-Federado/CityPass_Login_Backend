package ar.edu.uade.citypass.loginfederado.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "login_attempts")
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(nullable = false)
    private boolean successful;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected LoginAttempt() {
    }

    public LoginAttempt(String username, String ipAddress, String userAgent, boolean successful, Instant attemptedAt) {
        this.username = username;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.successful = successful;
        this.attemptedAt = attemptedAt;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public boolean isSuccessful() { return successful; }
    public Instant getAttemptedAt() { return attemptedAt; }
}