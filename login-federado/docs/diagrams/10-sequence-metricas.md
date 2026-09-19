# Secuencia — Cálculo y publicación de métricas diarias

**Estado representado:** AS-IS del job programado de métricas.

```mermaid
sequenceDiagram
    autonumber
    participant SCH as Spring Scheduler
    participant MP as MetricsPublisher
    participant MS as MetricsService
    participant LAR as LoginAttemptRepository
    participant RTR as RefreshTokenRepository
    participant DB as PostgreSQL
    participant EP as LoggingEventPublisher
    participant LOG as Logs de aplicación

    Note over SCH,MP: Todos los días a las 00:05 UTC se procesa el día anterior
    SCH->>MP: publishDailyMetrics()
    MP->>MS: buildDailyEvent(fechaAnterior)

    MS->>LAR: countDistinctActiveUsersBetween(día)
    LAR->>DB: SELECT DAU
    DB-->>LAR: cantidad

    MS->>LAR: countDistinctActiveUsersBetween(últimos 30 días)
    LAR->>DB: SELECT MAU
    DB-->>LAR: cantidad

    MS->>LAR: countSuccessfulLoginsByHourBetween(día)
    LAR->>DB: SELECT logins por hora
    DB-->>LAR: buckets horarios

    MS->>RTR: findClosedSessionSpansEndedBetween(día)
    RTR->>DB: SELECT cadenas cerradas y duración
    DB-->>RTR: sesiones cerradas

    MS-->>MP: DailyLoginMetricEvent con DAU, MAU, horarios y duración
    MP->>EP: publish(eventType, evento)
    EP->>LOG: JSON serializado
    Note over EP,LOG: Estado actual: no se envía a Kafka/RabbitMQ
```

## Evolución prevista

Cuando exista el contrato EDA definitivo, `LoggingEventPublisher` podrá reemplazarse por un adaptador de broker sin modificar `MetricsPublisher` ni `MetricsService`. Hasta entonces, cualquier diagrama TO-BE debe diferenciar claramente el broker planificado del logging actual.
