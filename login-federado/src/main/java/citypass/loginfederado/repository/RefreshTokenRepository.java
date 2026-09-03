package citypass.loginfederado.repository;

import citypass.loginfederado.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

        Optional<RefreshToken> findByTokenHash(String tokenHash);

        /**
     * Revoca TODA la cadena en un solo UPDATE persistido (reuso = robo).
     * Devuelve la cantidad de filas afectadas.
        */
        @Modifying
        @Query("update RefreshToken t set t.revokedAt = :now " +
                "where t.chainId = :chainId and t.revokedAt is null")
        int revokeChain(@Param("chainId") UUID chainId, @Param("now") Instant now);

    /** Logout global de una persona: mata todas sus sesiones activas. */
        @Modifying
        @Query("update RefreshToken t set t.revokedAt = :now " +
                "where t.sub = :sub and t.revokedAt is null")
        int revokeAllForSub(@Param("sub") String sub, @Param("now") Instant now);


        /**
     * Sesiones que TERMINARON dentro de [from, to) (logout, robo detectado,
     * o expiración silenciosa) — excluye sesiones todavía activas. La
     * agregación por chain_id se hace sobre TODAS sus filas (no solo las
     * del rango), para no cortar la cadena a la mitad; el filtro por fecha
     * se aplica DESPUÉS, sobre el fin ya calculado de cada sesión completa.
     */
        @Query(value = """
                WITH sessions AS (
                SELECT chain_id,
                        MIN(issued_at) AS started_at,
                        MAX(COALESCE(revoked_at, expires_at)) AS ended_at,
                        BOOL_OR(revoked_at IS NULL AND expires_at > now()) AS still_active
                FROM refresh_tokens
                GROUP BY chain_id
                )
        SELECT chain_id AS chainId,
                        started_at AS startedAt,
                        ended_at AS endedAt,
                        still_active AS stillActive
                FROM sessions
                WHERE NOT still_active
                AND ended_at >= :from
                AND ended_at < :to
                """, nativeQuery = true)
                List<SessionSpanProjection> findClosedSessionSpansEndedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
