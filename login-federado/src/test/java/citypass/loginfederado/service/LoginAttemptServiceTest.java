package citypass.loginfederado.service;

import citypass.loginfederado.config.LockoutProperties;
import citypass.loginfederado.exception.AccountLockedException;
import citypass.loginfederado.model.LoginAttempt;
import citypass.loginfederado.model.LoginLockout;
import citypass.loginfederado.repository.LoginAttemptRepository;
import citypass.loginfederado.repository.LoginLockoutRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginAttemptServiceTest {

    private LoginAttemptRepository repository;
    private LoginLockoutRepository lockoutRepository;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        repository = mock(LoginAttemptRepository.class);
        lockoutRepository = mock(LoginLockoutRepository.class);

        service = new LoginAttemptService(
                repository,
                lockoutRepository,
                new LockoutProperties(5, 15, 10)
        );
    }

    @Test
    void allowsLoginWhenNoLockoutExists() {
        when(lockoutRepository.findById("jperez"))
                .thenReturn(Optional.empty());

        assertThatCode(
                () -> service.assertNotLocked("jperez")
        ).doesNotThrowAnyException();
    }

    @Test
    void blocksWhenLockoutIsStillActive() {
        LoginLockout lockout = new LoginLockout(
                "jperez",
                Instant.now().plusSeconds(5 * 60)
        );

        when(lockoutRepository.findById("jperez"))
                .thenReturn(Optional.of(lockout));

        assertThatThrownBy(
                () -> service.assertNotLocked("jperez")
        ).isInstanceOf(AccountLockedException.class);

        verify(lockoutRepository, never()).delete(lockout);
        verify(repository, never())
                .deleteByUsernameAndSuccessfulFalse("jperez");
    }

    @Test
    void allowsLoginWhenLockoutExpired() {
        LoginLockout lockout = new LoginLockout(
                "jperez",
                Instant.now().minusSeconds(60)
        );

        when(lockoutRepository.findById("jperez"))
                .thenReturn(Optional.of(lockout));

        assertThatCode(
                () -> service.assertNotLocked("jperez")
        ).doesNotThrowAnyException();

        verify(lockoutRepository).delete(lockout);

        verify(repository)
                .deleteByUsernameAndSuccessfulFalse("jperez");
    }

    @Test
    void createsLockoutWhenFailedAttemptThresholdIsReached() {
        when(
                repository.countByUsernameAndSuccessfulFalseAndAttemptedAtAfter(
                        eq("jperez"),
                        any(Instant.class)
                )
        ).thenReturn(5L);

        service.recordAttempt(
                "jperez",
                "10.0.0.1",
                "JUnit",
                false
        );

        verify(lockoutRepository).save(
                argThat(lockout ->
                        lockout.getUsername().equals("jperez")
                                && lockout.getLockedUntil().isAfter(Instant.now())
                )
        );
    }

    @Test
    void doesNotCreateLockoutBelowThreshold() {
        when(
                repository.countByUsernameAndSuccessfulFalseAndAttemptedAtAfter(
                        eq("jperez"),
                        any(Instant.class)
                )
        ).thenReturn(4L);

        service.recordAttempt(
                "jperez",
                "10.0.0.1",
                "JUnit",
                false
        );

        verify(lockoutRepository, never())
                .save(any(LoginLockout.class));
    }

    @Test
    void successfulAttemptDoesNotCreateLockout() {
        service.recordAttempt(
                "jperez",
                "10.0.0.1",
                "JUnit",
                true
        );

        verify(lockoutRepository, never())
                .save(any(LoginLockout.class));

        verify(
                repository,
                never()
        ).countByUsernameAndSuccessfulFalseAndAttemptedAtAfter(
                eq("jperez"),
                any(Instant.class)
        );
    }

    @Test
    void recordsAttemptWithAllSecurityMetadata() {
        service.recordAttempt(
                "jperez",
                "10.0.0.1",
                "JUnit",
                false
        );

        ArgumentCaptor<LoginAttempt> captor =
                ArgumentCaptor.forClass(LoginAttempt.class);

        verify(repository).save(captor.capture());

        LoginAttempt attempt = captor.getValue();

        assertThat(attempt.getUsername()).isEqualTo("jperez");
        assertThat(attempt.getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(attempt.getUserAgent()).isEqualTo("JUnit");
        assertThat(attempt.isSuccessful()).isFalse();
        assertThat(attempt.getAttemptedAt()).isNotNull();
    }
}