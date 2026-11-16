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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

    private static final Instant NOW = Instant.parse("2026-11-15T15:00:00Z");

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
                new LockoutProperties(5, 15, 10),
                Clock.fixed(NOW, ZoneOffset.UTC)
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
                NOW.plusSeconds(5 * 60)
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
                NOW.minusSeconds(60)
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
                                && lockout.getLockedUntil().equals(NOW.plusSeconds(10 * 60))
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

        when(lockoutRepository.findAllById(List.of("jperez"))).thenReturn(List.of());
        var status = service.getLockoutStatuses(List.of("jperez")).get("jperez");
        assertThat(status.locked()).isFalse();
        assertThat(status.lockoutUntil()).isNull();
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

    @Test
    void clearsLockoutAndFailureWindow() {
        LoginLockout lockout = new LoginLockout(
                "jperez",
                Instant.now().plusSeconds(60)
        );
        when(lockoutRepository.findById("jperez")).thenReturn(Optional.of(lockout));

        service.clearLockout("jperez");

        verify(lockoutRepository).delete(lockout);
        verify(repository).deleteByUsernameAndSuccessfulFalse("jperez");

        when(lockoutRepository.findAllById(List.of("jperez"))).thenReturn(List.of());
        var status = service.getLockoutStatuses(List.of("jperez")).get("jperez");
        assertThat(status.locked()).isFalse();
        assertThat(status.lockoutUntil()).isNull();
    }

    @Test
    void clearLockoutIsIdempotentWhenNoLockoutExists() {
        when(lockoutRepository.findById("jperez")).thenReturn(Optional.empty());

        assertThatCode(() -> service.clearLockout("jperez"))
                .doesNotThrowAnyException();

        verify(lockoutRepository, never()).delete(any(LoginLockout.class));
        verify(repository).deleteByUsernameAndSuccessfulFalse("jperez");
    }

    @Test
    void reportsNotLockedWhenNoLockoutExists() {
        when(lockoutRepository.findAllById(List.of("jperez"))).thenReturn(List.of());

        var status = service.getLockoutStatuses(List.of("jperez")).get("jperez");

        assertThat(status.locked()).isFalse();
        assertThat(status.lockoutUntil()).isNull();
    }

    @Test
    void reportsActiveLockoutsAndTheirExactEndInOneBatch() {
        Instant lockedUntil = NOW.plusSeconds(10 * 60);
        when(lockoutRepository.findAllById(any()))
                .thenReturn(List.of(new LoginLockout("jperez", lockedUntil)));

        Map<String, LoginAttemptService.LockoutStatus> statuses =
                service.getLockoutStatuses(List.of("jperez", "mlopez"));

        assertThat(statuses.get("jperez").locked()).isTrue();
        assertThat(statuses.get("jperez").lockoutUntil()).isEqualTo(lockedUntil);
        assertThat(statuses.get("mlopez").locked()).isFalse();
        assertThat(statuses.get("mlopez").lockoutUntil()).isNull();
        verify(lockoutRepository).findAllById(argThat(usernames -> {
            var values = new java.util.HashSet<String>();
            usernames.forEach(values::add);
            return values.equals(java.util.Set.of("jperez", "mlopez"));
        }));
    }

    @Test
    void reportsExpiredLockoutAsInactiveWithoutExposingItsOldEnd() {
        when(lockoutRepository.findAllById(List.of("jperez")))
                .thenReturn(List.of(new LoginLockout("jperez", NOW)));

        var status = service.getLockoutStatuses(List.of("jperez")).get("jperez");

        assertThat(status.locked()).isFalse();
        assertThat(status.lockoutUntil()).isNull();
    }

    @Test
    void springSelectsTheProductionConstructorWhenCreatingTheBean() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LoginAttemptRepository.class, () -> repository);
            context.registerBean(LoginLockoutRepository.class, () -> lockoutRepository);
            context.registerBean(LockoutProperties.class,
                    () -> new LockoutProperties(5, 15, 10));
            context.register(LoginAttemptService.class);

            context.refresh();

            assertThat(context.getBean(LoginAttemptService.class)).isNotNull();
        }
    }
}
