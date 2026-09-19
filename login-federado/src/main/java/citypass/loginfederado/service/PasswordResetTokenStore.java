package citypass.loginfederado.service;

import citypass.loginfederado.model.PasswordResetToken;
import citypass.loginfederado.repository.PasswordResetTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistencia de tokens de recupero con las dos garantías atómicas:
 *
 * - issue: borra los tokens previos del sub e inserta el nuevo EN LA MISMA
 *   transacción → solo un token activo por cuenta (el anterior muere).
 * - consumeIfUsable: UPDATE condicional (solo si sigue activo y vigente).
 *   Cierra el TOCTOU del doble canje concurrente con el mismo patrón que
 *   RefreshTokenRepository.revokeIfActive: el perdedor no toca filas y se
 *   lo trata como token inválido.
 */
@Service
public class PasswordResetTokenStore {

    private final PasswordResetTokenRepository repository;

    public PasswordResetTokenStore(PasswordResetTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PasswordResetToken issue(String sub, String uid, String tokenHash, int ttlMinutes) {
        Instant now = Instant.now();
        repository.deleteBySub(sub);
        return repository.save(new PasswordResetToken(
                sub, uid, tokenHash, now, now.plusSeconds((long) ttlMinutes * 60)));
    }

    /** Lectura sin transacción: solo para localizar el candidato a canjear. */
    public Optional<PasswordResetToken> findByHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash);
    }

    /**
     * Marca usado solo si sigue activo y vigente. Devuelve true si este
     * llamado ganó el canje; false = ya usado, vencido o lo ganó otro hilo.
     */
    @Transactional
    public boolean consumeIfUsable(String tokenHash, Instant now) {
        return repository.markUsedIfActive(tokenHash, now) == 1;
    }

    /** Libera el token pendiente (p.ej. mail no entregado → reintento). */
    @Transactional
    public void discard(String sub) {
        repository.deleteBySub(sub);
    }
}
