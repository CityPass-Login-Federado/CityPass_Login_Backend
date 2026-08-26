package citypass.loginfederado.service;

import citypass.loginfederado.config.LockoutProperties;
import citypass.loginfederado.exception.AccountLockedException;
import citypass.loginfederado.model.LoginAttempt;
import citypass.loginfederado.repository.LoginAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginAttemptServiceTest {
    private LoginAttemptRepository repository;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        repository = mock(LoginAttemptRepository.class);
        service = new LoginAttemptService(repository, new LockoutProperties(5, 15));
    }

    @Test
    void allowsLoginBelowThreshold() {
        when(repository.countByUsernameAndSuccessfulFalseAndAttemptedAtAfter(eq("jperez"), any(Instant.class)))
                .thenReturn(4L);
        assertThatCode(() -> service.assertNotLocked("jperez")).doesNotThrowAnyException();
    }

    @Test
    void blocksAtThreshold() {
        when(repository.countByUsernameAndSuccessfulFalseAndAttemptedAtAfter(eq("jperez"), any(Instant.class)))
                .thenReturn(5L);
        assertThatThrownBy(() -> service.assertNotLocked("jperez"))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void recordsAttemptWithAllSecurityMetadata() {
        service.recordAttempt("jperez", "10.0.0.1", "JUnit", false);
        ArgumentCaptor<LoginAttempt> captor = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(repository).save(captor.capture());
        LoginAttempt attempt = captor.getValue();
        assertThat(attempt.getUsername()).isEqualTo("jperez");
        assertThat(attempt.getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(attempt.getUserAgent()).isEqualTo("JUnit");
        assertThat(attempt.isSuccessful()).isFalse();
        assertThat(attempt.getAttemptedAt()).isNotNull();
    }
}
