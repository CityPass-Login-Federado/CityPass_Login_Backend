package citypass.loginfederado.repository;

import citypass.loginfederado.model.RevokedAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface RevokedAccessTokenRepository extends JpaRepository<RevokedAccessToken, String> {

    /** Purga de mantenimiento: un jti expirado ya no puede revocar nada. */
    long deleteByExpiresAtBefore(Instant cutoff);
}
