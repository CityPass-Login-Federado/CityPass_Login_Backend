package citypass.loginfederado.repository;

import citypass.loginfederado.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Un solo token activo por cuenta: al emitir uno nuevo se borran TODOS
     * los anteriores de ese sub (activos, usados o vencidos) en la misma
     * transacción que inserta el reemplazo. El anterior queda inválido.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.sub = :sub")
    int deleteBySub(@Param("sub") String sub);

    /**
     * Consumo atómico: marca usado SOLO si sigue activo y vigente. Devuelve
     * 1 si este llamado ganó el canje, 0 si otro hilo lo canjeó antes, ya
     * estaba usado o venció — el perdedor se trata como token inválido.
     * Mismo patrón anti-TOCTOU que revokeIfActive de refresh tokens.
     */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now " +
            "where t.tokenHash = :tokenHash and t.usedAt is null and t.expiresAt > :now")
    int markUsedIfActive(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** Limpieza de tokens vencidos (job o mantenimiento). */
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :now")
    int deleteExpiredBefore(@Param("now") Instant now);
}
