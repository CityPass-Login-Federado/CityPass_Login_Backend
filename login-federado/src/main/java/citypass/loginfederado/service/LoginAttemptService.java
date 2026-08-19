package citypass.loginfederado.service;

import citypass.loginfederado.config.LockoutProperties;
import citypass.loginfederado.exception.AccountLockedException;
import citypass.loginfederado.model.LoginAttempt;
import citypass.loginfederado.repository.LoginAttemptRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Bloqueo por ventana deslizante: si hay N o más intentos fallidos en los
 * últimos M minutos, la cuenta queda bloqueada. No hay un flag de
 * "bloqueado" ni desbloqueo manual -- a medida que los intentos viejos
 * salen de la ventana, se destraba solo.
 *
 * Esta es la Capa 1 (regla dura) del control de fuerza bruta. La Capa 2
 * (score de riesgo por IA/ML sobre patrones de comportamiento) se agrega
 * después, consultando a este mismo servicio como piso de seguridad que
 * sigue funcionando aunque el modelo de IA no esté disponible.
 */
@Service
public class LoginAttemptService {

    private final LoginAttemptRepository loginAttemptRepository;
    private final LockoutProperties lockoutProperties;

    public LoginAttemptService(LoginAttemptRepository loginAttemptRepository, LockoutProperties lockoutProperties) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.lockoutProperties = lockoutProperties;
    }

    /** Lanza AccountLockedException si el usuario superó el umbral de intentos fallidos. */
    public void assertNotLocked(String username) {
        Instant windowStart = Instant.now().minusSeconds(lockoutProperties.windowMinutes() * 60);
        long recentFailures = loginAttemptRepository
                .countByUsernameAndSuccessfulFalseAndAttemptedAtAfter(username, windowStart);

        if (recentFailures >= lockoutProperties.maxFailedAttempts()) {
            throw new AccountLockedException(
                    "Cuenta bloqueada temporalmente por múltiples intentos fallidos. Intente nuevamente más tarde.");
        }
    }

    public void recordAttempt(String username, String ipAddress, String userAgent, boolean successful) {
        loginAttemptRepository.save(
                new LoginAttempt(username, ipAddress, userAgent, successful, Instant.now())
        );
    }
}