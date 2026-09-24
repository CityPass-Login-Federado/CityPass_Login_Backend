package citypass.loginfederado.service;

import citypass.loginfederado.config.LockoutProperties;
import citypass.loginfederado.exception.AccountLockedException;
import citypass.loginfederado.model.LoginAttempt;
import citypass.loginfederado.model.LoginLockout;
import citypass.loginfederado.repository.LoginAttemptRepository;
import citypass.loginfederado.repository.LoginLockoutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class LoginAttemptService {

    private final LoginAttemptRepository loginAttemptRepository;
    private final LoginLockoutRepository loginLockoutRepository;
    private final LockoutProperties lockoutProperties;

    public LoginAttemptService(
            LoginAttemptRepository loginAttemptRepository,
            LoginLockoutRepository loginLockoutRepository,
            LockoutProperties lockoutProperties
    ) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.loginLockoutRepository = loginLockoutRepository;
        this.lockoutProperties = lockoutProperties;
    }

    /**
     * Throws AccountLockedException if the account currently has
     * an active temporary lockout.
     */
    @Transactional
    public void assertNotLocked(String username) {
        Instant now = Instant.now();

        loginLockoutRepository.findById(username)
                .ifPresent(lockout -> {

                    if (now.isBefore(lockout.getLockedUntil())) {
                        throw new AccountLockedException(
                                "Cuenta bloqueada temporalmente por múltiples intentos fallidos. Intente nuevamente más tarde."
                        );
                    }

                    // Lockout expired: remove it and reset previous failures.
                    loginLockoutRepository.delete(lockout);
                    loginAttemptRepository
                            .deleteByUsernameAndSuccessfulFalse(username);
                });
    }

    /**
     * Records an authentication attempt.
     *
     * When the configured failed-attempt threshold is reached inside
     * the configured time window, a temporary lockout is created.
     */
    public void recordAttempt(
            String username,
            String ipAddress,
            String userAgent,
            boolean successful
    ) {
        Instant now = Instant.now();

        loginAttemptRepository.save(
                new LoginAttempt(
                        username,
                        ipAddress,
                        userAgent,
                        successful,
                        now
                )
        );

        if (successful) {
            return;
        }

        Instant windowStart = now.minusSeconds(
                lockoutProperties.windowMinutes() * 60
        );

        long recentFailures = loginAttemptRepository
                .countByUsernameAndSuccessfulFalseAndAttemptedAtAfter(
                        username,
                        windowStart
                );

        if (recentFailures >= lockoutProperties.maxFailedAttempts()) {

            Instant lockedUntil = now.plusSeconds(
                    lockoutProperties.lockoutMinutes() * 60
            );

            loginLockoutRepository.save(
                    new LoginLockout(
                            username,
                            lockedUntil
                    )
            );
        }
    }
}