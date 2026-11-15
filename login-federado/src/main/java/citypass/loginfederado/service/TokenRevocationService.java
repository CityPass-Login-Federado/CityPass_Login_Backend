package citypass.loginfederado.service;

import citypass.loginfederado.model.RevokedAccessToken;
import citypass.loginfederado.model.RevokedSubjectCutoff;
import citypass.loginfederado.repository.RevokedAccessTokenRepository;
import citypass.loginfederado.repository.RevokedSubjectCutoffRepository;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Revocación de access tokens por `jti` (denylist de logout) y por corte
 * temporal de sujeto (`revoked_before` en cambio/recupero de contraseña o baja).
 *
 * El access token es stateless: emitido, vive hasta expirar salvo que su `jti`
 * figure aquí o su `iat` sea anterior/igual al corte del `sub`. Las entradas
 * expiradas se purgan periódicamente porque el `exp` del JWT ya las invalida.
 */
@Service
public class TokenRevocationService {

    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");
    private static final long MIN_CUTOFF_TTL_MINUTES = 15;

    private final RevokedAccessTokenRepository repository;
    private final RevokedSubjectCutoffRepository cutoffRepository;
    private final long accessTokenExpirationMinutes;

    @Autowired
    public TokenRevocationService(
            RevokedAccessTokenRepository repository,
            RevokedSubjectCutoffRepository cutoffRepository,
            @Value("${jwt.access-token-expiration-minutes:15}") long accessTokenExpirationMinutes) {
        this.repository = repository;
        this.cutoffRepository = cutoffRepository;
        this.accessTokenExpirationMinutes = Math.max(accessTokenExpirationMinutes, MIN_CUTOFF_TTL_MINUTES);
    }

    public TokenRevocationService(RevokedAccessTokenRepository repository,
                                  RevokedSubjectCutoffRepository cutoffRepository) {
        this(repository, cutoffRepository, MIN_CUTOFF_TTL_MINUTES);
    }

    @Transactional
    public void revoke(String jti, String sub, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return;
        }
        if (repository.existsById(jti)) {
            return;
        }
        repository.save(new RevokedAccessToken(jti, sub, expiresAt));
    }

    public boolean isRevoked(String jti) {
        return jti != null && !jti.isBlank() && repository.existsById(jti);
    }

    /**
     * Revoca el access token presentado en el logout. Si el token está
     * expirado, es basura o no tiene jti, no hay nada que denylistar: se
     * ignora en silencio (igual que el logout con refresh desconocido).
     */
    public void revokeRawAccessToken(String rawAccessToken) {
        if (rawAccessToken == null || rawAccessToken.isBlank()) {
            return;
        }
        try {
            JWTClaimsSet claims = SignedJWT.parse(rawAccessToken).getJWTClaimsSet();
            if (claims.getExpirationTime() == null) {
                return;
            }
            revoke(claims.getJWTID(), claims.getSubject(), claims.getExpirationTime().toInstant());
        } catch (Exception ex) {
            securityLog.debug("Logout con access token no parseable: no se denylista ({})",
                    ex.toString());
        }
    }

    /**
     * Cambio/recupero de contraseña o baja administrativa: fija un corte
     * `revoked_before = now` para que todo access token previo de este `sub`
     * quede rechazado hasta que expire naturalmente.
     */
    @Transactional
    public void revokeAllForSub(String sub) {
        if (sub == null || sub.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(accessTokenExpirationMinutes * 60);
        RevokedSubjectCutoff cutoff = cutoffRepository.findById(sub)
                .map(existing -> {
                    existing.advanceCutoff(now, expiresAt);
                    return existing;
                })
                .orElseGet(() -> new RevokedSubjectCutoff(sub, now, expiresAt));
        cutoffRepository.save(cutoff);
    }

    /** Mantenimiento: borra entradas ya expiradas (el `exp` del token ya los mata). */
    @Scheduled(fixedDelayString = "${security.token-revocation.purge-interval-ms:3600000}")
    @Transactional
    public int purgeExpired() {
        Instant now = Instant.now();
        int removedTokens = (int) repository.deleteByExpiresAtBefore(now);
        int removedCutoffs = (int) cutoffRepository.deleteByExpiresAtBefore(now);
        int total = removedTokens + removedCutoffs;
        if (total > 0) {
            securityLog.info("Denylist de access tokens: {} entradas expiradas purgadas", total);
        }
        return total;
    }
}
