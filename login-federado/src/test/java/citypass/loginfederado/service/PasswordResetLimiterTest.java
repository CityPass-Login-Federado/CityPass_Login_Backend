package citypass.loginfederado.service;

import citypass.loginfederado.config.PasswordResetProperties;
import citypass.loginfederado.repository.PasswordResetRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El limiter es la reserva atómica del cupo: aceptar inserta la fila,
 * rechazar no guarda nada (el endpoint responde 204 igual en ambos casos).
 */
class PasswordResetLimiterTest {

    private final PasswordResetProperties properties = new PasswordResetProperties(
            "no-reply@citypass.local", true, "http://localhost:3000/reset-password",
            30, 5, 3, 50);

    private PasswordResetRequestRepository repository;
    private PasswordResetLimiter limiter;

    @BeforeEach
    void setUp() {
        repository = mock(PasswordResetRequestRepository.class);
        limiter = new PasswordResetLimiter(repository, properties);
    }

    @Test
    void firstRequestIsAcceptedAndRecorded() {
        assertThat(limiter.tryAcquire("jperez", "10.0.0.1")).isTrue();

        verify(repository).save(argThat(req ->
                req.getUid().equals("jperez")
                        && "10.0.0.1".equals(req.getIpAddress())
                        && req.getRequestedAt() != null));
    }

    @Test
    void uidIsNormalizedBeforeCounting() {
        limiter.tryAcquire("  Jperez ", "10.0.0.1");

        // Dos consultas por diseño (cooldown + tope horario), ambas normalizadas.
        verify(repository, org.mockito.Mockito.times(2))
                .countByUidAndRequestedAtAfter(eq("jperez"), any(Instant.class));
        verify(repository, never()).countByUidAndRequestedAtAfter(
                argThat(uid -> !uid.equals("jperez")), any(Instant.class));
        verify(repository).save(argThat(req -> req.getUid().equals("jperez")));
    }

    @Test
    void cooldownBlocksImmediateRetryWithoutRecording() {
        when(repository.countByUidAndRequestedAtAfter(eq("jperez"), any(Instant.class)))
                .thenReturn(1L);

        assertThat(limiter.tryAcquire("jperez", "10.0.0.1")).isFalse();

        verify(repository, never()).save(any());
    }

    @Test
    void accountHourlyCapBlocksWithoutRecording() {
        // Nada en la ventana de cooldown (5 min) pero 3 en la última hora.
        when(repository.countByUidAndRequestedAtAfter(eq("jperez"), any(Instant.class)))
                .thenAnswer(invocation -> {
                    Instant since = invocation.getArgument(1);
                    return since.isAfter(Instant.now().minusSeconds(600)) ? 0L : 3L;
                });

        assertThat(limiter.tryAcquire("jperez", "10.0.0.1")).isFalse();

        verify(repository, never()).save(any());
    }

    @Test
    void ipHourlyCapBlocksWithoutRecording() {
        when(repository.countByUidAndRequestedAtAfter(anyString(), any(Instant.class)))
                .thenReturn(0L);
        when(repository.countByIpAddressAndRequestedAtAfter(eq("10.0.0.1"), any(Instant.class)))
                .thenReturn(50L);

        assertThat(limiter.tryAcquire("otro", "10.0.0.1")).isFalse();

        verify(repository, never()).save(any());
    }

    @Test
    void blankIpSkipsIpCheck() {
        when(repository.countByUidAndRequestedAtAfter(anyString(), any(Instant.class)))
                .thenReturn(0L);

        assertThat(limiter.tryAcquire("jperez", null)).isTrue();

        verify(repository, never()).countByIpAddressAndRequestedAtAfter(any(), any());
        verify(repository).save(any());
    }
}
