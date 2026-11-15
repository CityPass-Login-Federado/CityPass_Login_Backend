package citypass.loginfederado.service;

import citypass.loginfederado.config.LoginRateLimitProperties;
import citypass.loginfederado.exception.TooManyRequestsException;
import citypass.loginfederado.repository.LoginAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tope de intentos de sesión por IP en ventana deslizante (ADR-004 cubre el
 * lockout por usuario; esto cubre el abuso desde un mismo origen).
 *
 * Para /auth/login cuenta los intentos ya registrados en `login_attempts`
 * (que incluye la IP). Para /auth/refresh — que no escribe en `login_attempts`
 * para no contaminar las features del detector de anomalías ni las métricas —
 * suma además una ventana deslizante en memoria por IP. La IP que recibe ya
 * viene resuelta por ClientIpResolver (no spoofeable).
 */
@Service
public class LoginRateLimiter {

    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");
    private static final int MAX_TRACKED_REFRESH_IPS = 10_000;

    private final LoginAttemptRepository repository;
    private final LoginRateLimitProperties properties;
    private final Map<String, Deque<Instant>> refreshHitsByIp = new ConcurrentHashMap<>();

    public LoginRateLimiter(LoginAttemptRepository repository,
                            LoginRateLimitProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Lanza TooManyRequestsException (→ HTTP 429) si la IP superó el tope en
     * `login_attempts`. Sin IP (null/blank) no hay nada que contar: se permite.
     */
    public void assertWithinLimit(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        Instant windowStart = Instant.now()
                .minusSeconds(properties.windowMinutes() * 60);

        long attempts = repository.countByIpAddressAndAttemptedAtAfter(ipAddress, windowStart);

        if (attempts >= properties.maxPerIpPerWindow()) {
            securityLog.warn("Rate limit por IP excedido: ip={} intentos={} ventana={}min",
                    ipAddress, attempts, properties.windowMinutes());
            throw new TooManyRequestsException(
                    "Demasiadas solicitudes. Intente nuevamente más tarde.");
        }
    }

    /**
     * Tope por IP específico para /auth/refresh: combina intentos de login
     * recientes en DB con los hits recientes a /auth/refresh en memoria.
     */
    public void assertWithinRefreshLimit(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(properties.windowMinutes() * 60);

        long loginAttempts = repository.countByIpAddressAndAttemptedAtAfter(ipAddress, windowStart);

        if (refreshHitsByIp.size() >= MAX_TRACKED_REFRESH_IPS && !refreshHitsByIp.containsKey(ipAddress)) {
            pruneStaleRefreshEntries(windowStart);
        }

        Deque<Instant> hits = refreshHitsByIp.computeIfAbsent(ipAddress, key -> new ArrayDeque<>());
        synchronized (hits) {
            evictOlderThan(hits, windowStart);
            long totalAttempts = loginAttempts + hits.size();
            if (totalAttempts >= properties.maxPerIpPerWindow()) {
                securityLog.warn("Rate limit de refresh por IP excedido: ip={} intentos={} ventana={}min",
                        ipAddress, totalAttempts, properties.windowMinutes());
                throw new TooManyRequestsException(
                        "Demasiadas solicitudes. Intente nuevamente más tarde.");
            }
            hits.addLast(now);
        }
    }

    private void pruneStaleRefreshEntries(Instant windowStart) {
        refreshHitsByIp.entrySet().removeIf(entry -> {
            Deque<Instant> deque = entry.getValue();
            synchronized (deque) {
                evictOlderThan(deque, windowStart);
                return deque.isEmpty();
            }
        });
    }

    private static void evictOlderThan(Deque<Instant> hits, Instant windowStart) {
        while (!hits.isEmpty() && hits.peekFirst().isBefore(windowStart)) {
            hits.removeFirst();
        }
    }
}
