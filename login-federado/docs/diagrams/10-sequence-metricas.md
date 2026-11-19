# Secuencia — Outbox y publicación de eventos de identidad

**Fecha de revisión:** 19 de noviembre de 2026

El backend produce eventos crudos de autenticación y los persiste antes de intentar su entrega. No calcula DAU, MAU ni agregados diarios; esas métricas corresponden a consumidores posteriores del flujo EDA.

```mermaid
sequenceDiagram
    autonumber
    participant S as AuthService / RefreshTokenService
    participant OS as EventOutboxService
    participant DB as PostgreSQL event_outbox
    participant R as EventOutboxRelay
    participant EP as EventPublisher
    participant LP as LoggingEventPublisher
    participant HP as EdaHttpEventPublisher
    participant OAuth as OAuth de Login Federado
    participant GW as Event Gateway - Grupo 1

    S->>OS: enqueue(RawAuthenticationEvent)
    Note over S,OS: identidad.login, identidad.refresh<br/>o identidad.logout
    OS->>DB: INSERT PENDING con authEventId estable
    OS-->>S: Evento durable
    S-->>S: El request funcional puede finalizar

    loop Cada eda.outbox.poll-interval-ms
        R->>OS: claimDue(batchSize, claimLease)
        OS->>DB: SELECT vencidos FOR UPDATE SKIP LOCKED
        DB-->>R: Lote PENDING reclamado
        loop Por cada evento
            R->>EP: publish(eventType, payload)
            alt Publicador predeterminado logging
                EP->>LP: Registrar el evento
                LP-->>R: OK
            else Publicador HTTP habilitado
                EP->>HP: Adaptar payload al contrato EDA
                opt Token ausente o próximo a vencer
                    HP->>OAuth: POST /oauth/token<br/>client_credentials de grupo2
                    OAuth-->>HP: JWT de servicio
                end
                HP->>GW: POST /api/v1/event-types/{fqn}/events<br/>Bearer JWT + data
                GW-->>HP: Resultado HTTP
                HP-->>R: OK o excepción
            end
            alt Entrega correcta
                R->>OS: markSent(id)
                OS->>DB: status=SENT, sent_at=now
            else Error transitorio y quedan intentos
                R->>OS: recordFailure(id, error, Retry-After)
                OS->>DB: attempts+1, next_attempt_at con backoff
            else Se alcanzó maxAttempts
                R->>OS: recordFailure(id, error)
                OS->>DB: status=FAILED, last_error
            end
        end
    end
```

## Garantías y límites

- El `id` de `event_outbox` coincide con `authEventId`; un reintento conserva la identidad del evento para permitir deduplicación en EDA.
- El lease y `FOR UPDATE SKIP LOCKED` evitan que dos instancias reclamen simultáneamente la misma fila.
- El *backoff* es exponencial y respeta un `Retry-After` de HTTP 429 cuando exige una espera mayor.
- La persistencia del Outbox usa una transacción propia y se ejecuta con manejo de error de mejor esfuerzo: un fallo al encolar queda registrado, pero no revierte el login, refresh o logout ya completado.
- `SENT` y `FAILED` quedan disponibles para operación; el código actual no purga automáticamente filas históricas.
- `EdaHttpEventPublisher` envía únicamente los datos de negocio. El Event Gateway completa la metadata del envelope.
