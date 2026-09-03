package citypass.loginfederado.metrics;

import citypass.loginfederado.repository.LoginAttemptRepository;
import citypass.loginfederado.repository.RefreshTokenRepository;
import citypass.loginfederado.repository.SessionSpanProjection;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MetricsService {

    private final LoginAttemptRepository loginAttemptRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public MetricsService(LoginAttemptRepository loginAttemptRepository,
                        RefreshTokenRepository refreshTokenRepository) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Arma el evento completo con las métricas del día `periodo` (UTC). */
    public DailyLoginMetricEvent buildDailyEvent(LocalDate periodo) {
        Instant dayStart = periodo.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = periodo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant thirtyDaysBefore = dayEnd.minusSeconds(30L * 24 * 60 * 60);

        long dau = loginAttemptRepository.countDistinctActiveUsersBetween(dayStart, dayEnd);
        long mau = loginAttemptRepository.countDistinctActiveUsersBetween(thirtyDaysBefore, dayEnd);

        List<HourlyLoginBucket> horarios = loginAttemptRepository
                .countSuccessfulLoginsByHourBetween(dayStart, dayEnd).stream()
                .map(r -> new HourlyLoginBucket(r.getHourOfDay(), r.getLoginCount()))
                .collect(Collectors.toList());

        List<SessionSpanProjection> sesionesCerradas =
                refreshTokenRepository.findClosedSessionSpansEndedBetween(dayStart, dayEnd);

        double[] duraciones = sesionesCerradas.stream()
                .mapToDouble(s -> Duration.between(s.getStartedAt(), s.getEndedAt()).getSeconds())
                .toArray();

        double promedio = duraciones.length > 0 ? Arrays.stream(duraciones).average().orElse(0) : 0;
        double minima = duraciones.length > 0 ? Arrays.stream(duraciones).min().orElse(0) : 0;
        double maxima = duraciones.length > 0 ? Arrays.stream(duraciones).max().orElse(0) : 0;

        return DailyLoginMetricEvent.of(
                periodo, dau, mau, horarios,
                sesionesCerradas.size(), promedio, minima, maxima
        );
    }
}