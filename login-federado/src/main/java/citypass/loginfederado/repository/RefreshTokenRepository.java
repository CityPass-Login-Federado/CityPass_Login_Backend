package citypass.loginfederado.repository;

import citypass.loginfederado.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
}
