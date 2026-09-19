package citypass.loginfederado.service;

import citypass.loginfederado.config.PasswordResetProperties;
import citypass.loginfederado.model.PasswordResetRequest;
import citypass.loginfederado.repository.PasswordResetRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Limitador del endpoint público de recupero (Issue: el endpoint permitía
 * cambios ilimitados de contraseña para cualquier cuenta conocida — spam de
 * buzón y negación de acceso por reemplazos repetidos).
 *
 * Tres frenos, en una sola transacción que RESERVA el hueco (inserta la
 * fila) cuando acepta — la reserva atómica evita que dos pedidos
 * concurrentes cuenten dos veces el mismo lugar:
 *
 * 1) Cooldown por cuenta: entre dos solicitudes aceptadas para la MISMA
 *    cuenta deben pasar al menos `cooldown-minutes`.
 * 2) Tope por cuenta y hora: como máximo `max-per-account-per-hour`.
 * 3) Tope por IP y hora: como máximo `max-per-ip-per-hour` (frena el
 *    barrido de muchas cuentas desde un mismo origen).
 *
 * Clave: el uid se NORMALIZA (trim + minúsculas) antes de contar, para que
 * " Jperez " y "jperez" compartan el mismo cupo. El rechazo es silencioso:
 * el llamador responde 204 igual (anti-enumeración); la causa va al log.
 */
@Service
public class PasswordResetLimiter {

    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

    private final PasswordResetRequestRepository repository;
    private final PasswordResetProperties properties;

    public PasswordResetLimiter(PasswordResetRequestRepository repository,
                                PasswordResetProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** Normalización canónica del identificador de cuenta para el cupo. */
    public static String normalize(String uid) {
        return uid == null ? "" : uid.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Intenta reservar un lugar. Devuelve true si la solicitud puede
     * procesarse (y deja la fila registrada), false si está limitada —
     * en ambos casos el endpoint responde 204.
     */
    @Transactional
    public boolean tryAcquire(String rawUid, String ipAddress) {
        String uid = normalize(rawUid);
        Instant now = Instant.now();

        long cooldownSeconds = (long) properties.cooldownMinutes() * 60;
        if (repository.countByUidAndRequestedAtAfter(uid, now.minusSeconds(cooldownSeconds)) > 0) {
            securityLog.warn("Recupero limitado por cooldown: uid={} ip={}", uid, ipAddress);
            return false;
        }

        long hourAgo = now.minusSeconds(3600).toEpochMilli();
        if (repository.countByUidAndRequestedAtAfter(uid, Instant.ofEpochMilli(hourAgo))
                >= properties.maxPerAccountPerHour()) {
            securityLog.warn("Recupero limitado por tope de cuenta: uid={} ip={}", uid, ipAddress);
            return false;
        }

        if (ipAddress != null && !ipAddress.isBlank()
                && repository.countByIpAddressAndRequestedAtAfter(ipAddress, Instant.ofEpochMilli(hourAgo))
                >= properties.maxPerIpPerHour()) {
            securityLog.warn("Recupero limitado por tope de IP: ip={} uid={}", ipAddress, uid);
            return false;
        }

        repository.save(new PasswordResetRequest(uid, ipAddress, now));
        return true;
    }
}
