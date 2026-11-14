# ADR-006: Puerto de eventos y adaptación progresiva al Event Bus

## Estado

Accepted — 14 de noviembre de 2026. La decisión aceptada es el desacoplamiento; el broker sigue pendiente.

## Contexto y Problema

El login debe informar hechos de dominio a otros squads, pero el repositorio no contiene contrato definitivo de broker, topics, envelope, autenticación ni garantías. Acoplar `AuthService` prematuramente a Kafka o RabbitMQ haría difícil cambiar de transporte.

## Opciones Consideradas (Pros y Contras de cada una)

### Llamadas REST a consumidores

- Pros: simples y observables.
- Contras: acoplamiento temporal y de disponibilidad; no es EDA.

### Kafka/RabbitMQ directo desde dominio

- Pros: entrega real inmediata.
- Contras: acoplamiento a SDK, configuración y contrato aún no acordado.

### Puerto `EventPublisher` + adaptador temporal

- Pros: dominio estable, pruebas sustituibles y migración localizada.
- Contras: el adaptador de logging no entrega eventos y puede ocultar la deuda.

### Outbox transaccional desde el inicio

- Pros: entrega al menos una vez y consistencia con SQL.
- Contras: requiere tabla, relay, idempotencia y broker definidos.

## Decisión Tomada y Justificación

`AuthService` depende de `EventPublisher`. La implementación actual `LoggingEventPublisher` serializa `UsuarioAutenticadoEvent` y lo escribe al log con tipo `usuario.autenticado`. El payload contiene `eventId`, `eventType`, `occurredAt`, `sub`, `uid`, `module` y `groups`.

Se acepta como seam arquitectónico y placeholder explícito hasta recibir el contrato del Squad 1; no se considera publicación EDA completa.

## Consecuencias Técnicas

- No hay broker, topic, credencial, DLQ, retry, outbox, confirmación, consumidor ni garantía de entrega.
- El evento se emite después de persistir tokens; un error de serialización solo se loguea.
- No se publican eventos de refresh, logout ni operaciones administrativas.
- La integración final debe agregar un adaptador, configuración por ambiente, envelope versionado, idempotencia y pruebas de contrato.
- Para tolerar fallos sin perder eventos, la opción recomendada es outbox transaccional sobre PostgreSQL más relay al broker acordado.
