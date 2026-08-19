package citypass.loginfederado.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementación temporal de EventPublisher mientras el Grupo 1 (EDA) no
 * publique el contrato definitivo del bus (topic naming, broker, formato
 * de envelope). Loguea el evento serializado en JSON.
 *
 * TODO: reemplazar por un publisher real (KafkaTemplate / RabbitTemplate)
 * apenas el contrato de EDA esté disponible. AuthService NO debería
 * cambiar: solo esta clase.
 */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);
    private final ObjectMapper objectMapper;

    public LoggingEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String eventType, Object payload) {
        try {
            log.info("[EVENTO PUBLICADO] tipo={} payload={}", eventType, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("No se pudo serializar el evento {}", eventType, e);
        }
    }
}