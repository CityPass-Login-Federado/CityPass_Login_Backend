package citypass.loginfederado.event;

/**
 * Abstracción sobre el bus de eventos. La implementación real (Kafka,
 * RabbitMQ, etc.) depende del contrato que publique el Grupo 1 (EDA);
 * mientras ese contrato no esté definido, se usa una implementación de
 * logging (ver LoggingEventPublisher) para no bloquear el desarrollo del
 * resto del módulo.
 */
public interface EventPublisher {
    void publish(String eventType, Object payload);
}