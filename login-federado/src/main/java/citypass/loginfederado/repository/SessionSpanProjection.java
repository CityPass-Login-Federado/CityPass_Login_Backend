package citypass.loginfederado.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Una "sesión" = una cadena de refresh tokens (chain_id), agregando TODOS
 * sus eslabones: el inicio es el primer issued_at, el fin es el revoked_at
 * del último eslabón (logout o robo detectado) o su expires_at si nunca se
 * revocó (la sesión murió en silencio, sin refrescar más).
 */
public interface SessionSpanProjection {
    UUID getChainId();
    Instant getStartedAt();
    Instant getEndedAt();
    boolean isStillActive();
}