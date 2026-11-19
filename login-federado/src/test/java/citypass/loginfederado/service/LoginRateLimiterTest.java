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

    // ── Refresh rate limiting tests ──────────────────────────────────────────

    @Test
    void refreshLimitSkipsWhenIpIsMissing() {
        assertThatCode(() -> limiter.assertWithinRefreshLimit(null)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.assertWithinRefreshLimit("  ")).doesNotThrowAnyException();

        verify(repository, never()).countByIpAddressAndAttemptedAtAfter(any(), any());
    }

    @Test
    void refreshLimitAllowsWhenUnderLimit() {
        when(repository.countByIpAddressAndAttemptedAtAfter(eq("1.2.3.4"), any()))
                .thenReturn(1L);

        // First call: 1 login attempt + 0 hits = 1 < 3 -> ok, adds 1 hit
        assertThatCode(() -> limiter.assertWithinRefreshLimit("1.2.3.4")).doesNotThrowAnyException();

        // Second call: 1 login attempt + 1 hit = 2 < 3 -> ok, adds 2nd hit
        assertThatCode(() -> limiter.assertWithinRefreshLimit("1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void refreshLimitRejectsWhenAtOrAboveLimit() {
        when(repository.countByIpAddressAndAttemptedAtAfter(eq("1.2.3.4"), any()))
                .thenReturn(2L);

        // First call: 2 logins + 0 hits = 2 < 3 -> ok, adds 1 hit
        assertThatCode(() -> limiter.assertWithinRefreshLimit("1.2.3.4")).doesNotThrowAnyException();

        // Second call: 2 logins + 1 hit = 3 >= 3 -> throws 429
        assertThatThrownBy(() -> limiter.assertWithinRefreshLimit("1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void refreshLimitRejectsWhenLoginAttemptsAloneReachLimit() {
        when(repository.countByIpAddressAndAttemptedAtAfter(eq("1.2.3.4"), any()))
                .thenReturn(3L);

        assertThatThrownBy(() -> limiter.assertWithinRefreshLimit("1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void refreshLimitEvictsStaleHitsAndPrunesMap() throws Exception {
        // Pre-populate refreshHitsByIp with entries including expired hits
        var field = LoginRateLimiter.class.getDeclaredField("refreshHitsByIp");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, java.util.Deque<Instant>>) field.get(limiter);

        // Add 10_000 dummy entries with empty/stale deques to trigger pruneStaleRefreshEntries
        Instant past = Instant.now().minusSeconds(PROPS.windowMinutes() * 60 + 100);
        for (int i = 0; i < 10_005; i++) {
            java.util.Deque<Instant> deque = new java.util.ArrayDeque<>();
            deque.add(past);
            map.put("10.0." + (i / 256) + "." + (i % 256), deque);
        }

        when(repository.countByIpAddressAndAttemptedAtAfter(eq("192.168.1.1"), any()))
                .thenReturn(0L);

        // Calling assertWithinRefreshLimit for a new IP triggers pruneStaleRefreshEntries
        limiter.assertWithinRefreshLimit("192.168.1.1");

        // The stale entries should have been pruned
        assertThat(map.size()).isLessThan(10_000);
    }
}
