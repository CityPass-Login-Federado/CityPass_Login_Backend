package citypass.loginfederado.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "login_lockouts")
public class LoginLockout {

    @Id
    private String username;

    @Column(name = "locked_until", nullable = false)
    private Instant lockedUntil;

    protected LoginLockout() {
    }

    public LoginLockout(
            String username,
            Instant lockedUntil
    ) {
        this.username = username;
        this.lockedUntil = lockedUntil;
    }

    public String getUsername() {
        return username;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(
            Instant lockedUntil
    ) {
        this.lockedUntil = lockedUntil;
    }
}