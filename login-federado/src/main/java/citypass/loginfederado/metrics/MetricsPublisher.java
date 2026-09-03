package citypass.loginfederado.metrics;

import citypass.loginfederado.event.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Job diario que calcula y publica las métricas para el equipo de
 * Analítica (Grupo 8). Corre a las 00:05 UTC, calculando el día que acaba
 * de cerrar completo — no el día en curso, que todavía está incompleto.
 */
@Component
public class MetricsPublisher {

    private static final Logger log = LoggerFactory.getLogger(MetricsPublisher.class);

    private final MetricsService metricsService;
    private final EventPublisher eventPublisher;

    public MetricsPublisher(MetricsService metricsService, EventPublisher eventPublisher) {
        this.metricsService = metricsService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "UTC") // cron diario
    //@Scheduled(cron = "*/30 * * * * *", zone = "UTC")  // TEMPORAL: cada 30 seg, solo para testear
    public void publishDailyMetrics() {
        LocalDate ayer = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        //LocalDate ayer = LocalDate.now(ZoneOffset.UTC); // TEMPORAL para testear: hoy, no ayer
        log.info("Calculando métricas diarias para el período {}", ayer);

        DailyLoginMetricEvent evento = metricsService.buildDailyEvent(ayer);
        eventPublisher.publish(evento.eventType(), evento);
    }
}