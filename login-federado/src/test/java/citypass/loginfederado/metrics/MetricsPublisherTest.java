package citypass.loginfederado.metrics;

import citypass.loginfederado.event.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MetricsPublisherTest {

    private MetricsService metricsService;
    private EventPublisher eventPublisher;
    private MetricsPublisher publisher;

    @BeforeEach
    void setUp() {
        metricsService = mock(MetricsService.class);
        eventPublisher = mock(EventPublisher.class);
        publisher = new MetricsPublisher(metricsService, eventPublisher);
    }

    @Test
    void publishesYesterdayInUtcWithTheEventTypeFromThePayload() {
        LocalDate expectedPeriod = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        DailyLoginMetricEvent event = DailyLoginMetricEvent.of(
                expectedPeriod, 2, 3, java.util.List.of(), 0, 0, 0, 0);
        when(metricsService.buildDailyEvent(expectedPeriod)).thenReturn(event);

        publisher.publishDailyMetrics();

        verify(metricsService).buildDailyEvent(expectedPeriod);
        verify(eventPublisher).publish(eq("identidad.metricas.diarias"), eq(event));
        verifyNoMoreInteractions(metricsService, eventPublisher);
        assertThat(event.eventType()).isEqualTo("identidad.metricas.diarias");
    }

    @Test
    void propagatesMetricCalculationFailureAndDoesNotPublishAnEvent() {
        LocalDate expectedPeriod = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        RuntimeException failure = new RuntimeException("metrics unavailable");
        when(metricsService.buildDailyEvent(expectedPeriod)).thenThrow(failure);

        assertThatThrownBy(() -> publisher.publishDailyMetrics())
                .isSameAs(failure);

        verify(metricsService).buildDailyEvent(expectedPeriod);
        verifyNoMoreInteractions(metricsService, eventPublisher);
    }
}