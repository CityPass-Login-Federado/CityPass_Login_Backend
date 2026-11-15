package citypass.loginfederado.service;

import citypass.loginfederado.config.LockoutProperties;
import citypass.loginfederado.exception.AccountLockedException;
import citypass.loginfederado.model.LoginAttempt;
import citypass.loginfederado.model.LoginLockout;
import citypass.loginfederado.repository.LoginAttemptRepository;
import citypass.loginfederado.repository.LoginLockoutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LoginAttemptService {

    private final LoginAttemptRepository loginAttemptRepository;
    private final LoginLockoutRepository loginLockoutRepository;
    private final LockoutProperties lockoutProperties;
    private final Clock clock;

    public LoginAttemptService(
            LoginAttemptRepository loginAttemptRepository,
            LoginLockoutRepository loginLockoutRepository,
            LockoutProperties lockoutProperties
    ) {
        this(loginAttemptRepository, loginLockoutRepository, lockoutProperties, Clock.systemUTC());
    }

    LoginAttemptService(
            LoginAttemptRepository loginAttemptRepository,
            LoginLockoutRepository loginLockoutRepository,
            LockoutProperties lockoutProperties,
            Clock clock
    ) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.loginLockoutRepository = loginLockoutRepository;
        this.lockoutProperties = lockoutProperties;
        this.clock = clock;
    }

    /**
     * Throws AccountLockedException if the account currently has
     * an active temporary lockout.
     */
    @Transactional
    public void assertNotLocked(String username) {
        Instant now = clock.instant();

        loginLockoutRepository.findById(username)
                .ifPresent(lockout -> {

                    if (evaluate(lockout, now).locked()) {
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
     * Returns the current temporary lockout state for all requested users in
     * one repository query. Expired rows are intentionally projected as not
     * locked; authentication performs the existing cleanup when that user
     * next logs in.
     */
    @Transactional(readOnly = true)
    public Map<String, LockoutStatus> getLockoutStatuses(Collection<String> usernames) {
        Map<String, LockoutStatus> statuses = new LinkedHashMap<>();
        usernames.forEach(username -> statuses.put(username, LockoutStatus.notLocked()));

        Instant now = clock.instant();
        loginLockoutRepository.findAllById(statuses.keySet())
                .forEach(lockout -> statuses.put(lockout.getUsername(), evaluate(lockout, now)));
        return Map.copyOf(statuses);
    }

    private static LockoutStatus evaluate(LoginLockout lockout, Instant now) {
        if (now.isBefore(lockout.getLockedUntil())) {
            return new LockoutStatus(true, lockout.getLockedUntil());
        }
        return LockoutStatus.notLocked();
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
        Instant now = clock.instant();

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

    /**
     * Clears the temporary brute-force lockout and its failed-attempt window.
     * Used both by the explicit panel action and after an anomaly approval;
     * clearing the lockout does not itself approve an anomaly.
     */
    @Transactional
    public void clearLockout(String username) {
        loginLockoutRepository.findById(username)
                .ifPresent(loginLockoutRepository::delete);
        loginAttemptRepository.deleteByUsernameAndSuccessfulFalse(username);
    }

    public record LockoutStatus(boolean locked, Instant lockoutUntil) {
        public static LockoutStatus notLocked() {
            return new LockoutStatus(false, null);
        }
    }
}
