package citypass.loginfederado.service;

import citypass.loginfederado.config.LoginRateLimitProperties;
import citypass.loginfederado.exception.TooManyRequestsException;
import citypass.loginfederado.repository.LoginAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginRateLimiterTest {

    private static final LoginRateLimitProperties PROPS =
            new LoginRateLimitProperties(3, 15);

    private LoginAttemptRepository repository;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        repository = mock(LoginAttemptRepository.class);
        limiter = new LoginRateLimiter(repository, PROPS);
    }

    @Test
    void allowsWhenUnderTheLimit() {
        when(repository.countByIpAddressAndAttemptedAtAfter(eq("1.2.3.4"), any()))
                .thenReturn(2L);

        assertThatCode(() -> limiter.assertWithinLimit("1.2.3.4"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenAtTheLimit() {
        when(repository.countByIpAddressAndAttemptedAtAfter(eq("1.2.3.4"), any()))
                .thenReturn(3L);

        assertThatThrownBy(() -> limiter.assertWithinLimit("1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void rejectsWhenAboveTheLimit() {
        when(repository.countByIpAddressAndAttemptedAtAfter(eq("1.2.3.4"), any()))
                .thenReturn(99L);

        assertThatThrownBy(() -> limiter.assertWithinLimit("1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void skipsCountingWhenIpIsMissing() {
        assertThatCode(() -> limiter.assertWithinLimit(null)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.assertWithinLimit("  ")).doesNotThrowAnyException();

        verify(repository, never()).countByIpAddressAndAttemptedAtAfter(any(), any());
    }

    @Test
    void usesConfiguredWindowForTheLookback() {
        when(repository.countByIpAddressAndAttemptedAtAfter(eq("1.2.3.4"), any()))
                .thenReturn(0L);

        limiter.assertWithinLimit("1.2.3.4");

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(repository).countByIpAddressAndAttemptedAtAfter(eq("1.2.3.4"), since.capture());

        Instant expected = Instant.now().minusSeconds(PROPS.windowMinutes() * 60);
        assertThat(since.getValue()).isBetween(
                expected.minusSeconds(5), expected.plusSeconds(5));
    }
}
