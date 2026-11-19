package citypass.loginfederado.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggingEventPublisherTest {

    @Test
    void publishesEnvelopeWithoutThrowing() {
        ObjectMapper objectMapper = new ObjectMapper();
        EdaEventEnvelopeFactory factory = new EdaEventEnvelopeFactory(objectMapper);
        LoggingEventPublisher publisher = new LoggingEventPublisher(objectMapper, factory);

        assertThatCode(() -> publisher.publish("identidad.login", Map.of("userSub", "u-1")))
                .doesNotThrowAnyException();
    }

    @Test
    void swallowsSerializationFailures() {
        ObjectMapper objectMapper = new ObjectMapper();
        EdaEventEnvelopeFactory factory = mock(EdaEventEnvelopeFactory.class);
        when(factory.create(anyString(), any(), anyString(), anyString(), any())).thenThrow(new RuntimeException("boom"));
        LoggingEventPublisher publisher = new LoggingEventPublisher(objectMapper, factory);

        assertThatCode(() -> publisher.publish("identidad.login", Map.of("userSub", "u-1")))
                .doesNotThrowAnyException();
    }
}
