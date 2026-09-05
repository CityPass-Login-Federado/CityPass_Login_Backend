package citypass.loginfederado.metrics;

import citypass.loginfederado.repository.HourlyLoginCount;
import citypass.loginfederado.repository.LoginAttemptRepository;
import citypass.loginfederado.repository.RefreshTokenRepository;
import citypass.loginfederado.repository.SessionSpanProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsServiceTest {

    private LoginAttemptRepository loginAttempts;
    private RefreshTokenRepository refreshTokens;
    private MetricsService service;

    @BeforeEach
    void setUp() {
        loginAttempts = mock(LoginAttemptRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        service = new MetricsService(loginAttempts, refreshTokens);
    }

    @Test
    void buildsDailyEventWithCountsHourlyBucketsAndSessionDurations() {
        LocalDate period = LocalDate.of(2026, 1, 15);
        when(loginAttempts.countDistinctActiveUsersBetween(any(), any()))
                .thenReturn(3L, 8L);
        when(loginAttempts.countSuccessfulLoginsByHourBetween(any(), any()))
                .thenReturn(List.of(hour(0, 2), hour(23, 4)));
        when(refreshTokens.findClosedSessionSpansEndedBetween(any(), any()))
                .thenReturn(List.of(
                        session(period, 60, 120),
                        session(period, 300, 900)
                ));

        DailyLoginMetricEvent event = service.buildDailyEvent(period);

        assertThat(event.eventType()).isEqualTo("identidad.metricas.diarias");
        assertThat(event.periodo()).isEqualTo(period);
        assertThat(event.usuariosActivosDiarios()).isEqualTo(3);
        assertThat(event.usuariosActivosMensuales()).isEqualTo(8);
        assertThat(event.horariosLogin())
                .extracting(HourlyLoginBucket::hour, HourlyLoginBucket::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 2L),
                        org.assertj.core.groups.Tuple.tuple(23, 4L));
        assertThat(event.sesionesFinalizadas()).isEqualTo(2);
        assertThat(event.duracionPromedioSegundos()).isEqualTo(330);
        assertThat(event.duracionMinimaSegundos()).isEqualTo(60);
        assertThat(event.duracionMaximaSegundos()).isEqualTo(600);
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isNotNull();

        verify(loginAttempts).countDistinctActiveUsersBetween(
                Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z"));
        verify(loginAttempts).countDistinctActiveUsersBetween(
                Instant.parse("2025-12-17T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z"));
    }

    @Test
    void usesZeroDurationStatisticsWhenThereAreNoClosedSessions() {
        when(loginAttempts.countDistinctActiveUsersBetween(any(), any())).thenReturn(0L);
        when(loginAttempts.countSuccessfulLoginsByHourBetween(any(), any())).thenReturn(List.of());
        when(refreshTokens.findClosedSessionSpansEndedBetween(any(), any())).thenReturn(List.of());

        DailyLoginMetricEvent event = service.buildDailyEvent(LocalDate.of(2026, 1, 15));

        assertThat(event.sesionesFinalizadas()).isZero();
        assertThat(event.duracionPromedioSegundos()).isZero();
        assertThat(event.duracionMinimaSegundos()).isZero();
        assertThat(event.duracionMaximaSegundos()).isZero();
        assertThat(event.horariosLogin()).isEmpty();
    }

    @Test
    void preservesRepositoryOrderAndMapsNullFreeHourlyRows() {
        when(loginAttempts.countDistinctActiveUsersBetween(any(), any())).thenReturn(1L, 1L);
        when(loginAttempts.countSuccessfulLoginsByHourBetween(any(), any()))
                .thenReturn(List.of(hour(8, 1), hour(8, 3)));
        when(refreshTokens.findClosedSessionSpansEndedBetween(any(), any())).thenReturn(List.of());

        DailyLoginMetricEvent event = service.buildDailyEvent(LocalDate.of(2026, 1, 15));

        assertThat(event.horariosLogin())
                .extracting(HourlyLoginBucket::hour, HourlyLoginBucket::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(8, 1L),
                        org.assertj.core.groups.Tuple.tuple(8, 3L));
    }

    private HourlyLoginCount hour(int hour, long count) {
                return new HourlyLoginCount() {
                        @Override
                        public Integer getHourOfDay() {
                                return hour;
                        }

                        @Override
                        public Long getLoginCount() {
                                return count;
                        }
                };
    }

    private SessionSpanProjection session(LocalDate period, long startSeconds, long endSeconds) {
        Instant day = period.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                return new SessionSpanProjection() {
                        @Override
                        public UUID getChainId() {
                                return UUID.randomUUID();
                        }

                        @Override
                        public Instant getStartedAt() {
                                return day.plusSeconds(startSeconds);
                        }

                        @Override
                        public Instant getEndedAt() {
                                return day.plusSeconds(endSeconds);
                        }

                        @Override
                        public boolean isStillActive() {
                                return false;
                        }
                };
    }
}
