# ADR-006: Event bus — placeholder con abstracción para Kafka/RabbitMQ

## Quiénes

| Nombre | Rol |
|--------|-----|
| Antonio Wu | Seguridad / Backend |

## Consideraciones

CityPass+ requiere comunicación asincrónica entre módulos (EDA — Event Driven Architecture). El Grupo 1 diseña el bus de eventos, contratos y políticas. El módulo de login necesita:
- Publicar eventos de autenticación (`usuario.autenticado`)
- Eventualmente consumir eventos relevantes (ej: `usuario.registrado`)

Restricciones y supuestos adicionales:
- La tecnología del broker no depende de este módulo: la decide el Grupo 1
- No podemos bloquear nuestro desarrollo ni nuestro testing esperando esa definición
- El código de dominio no debe acoplarse a una API de broker concreta

### Opciones consideradas

#### Opción A: Implementar Kafka directamente

| Pros | Contras |
|------|---------|
| Solución final, production-ready | Requiere que el Grupo 1 defina contratos primero |
| Alto throughput | Acoplamiento temprano a una tecnología específica |
| | Complejidad de configuración (bootstrap servers, topics, ACLs) |

#### Opción B: Implementar RabbitMQ directamente

| Pros | Contras |
|------|---------|
| Solución final | Mismos problemas que Kafka |
| Modelo de colas simple | |

#### Opción C: Abstracción con placeholder (logging)

| Pros | Contras |
|------|---------|
| Desacoplado del broker específico | Eventos solo se loguean, no se entregan |
| Listo para integrar cuando el Grupo 1 publique el contrato | Requiere implementación futura |
| Interfaz limpia: solo cambiar la implementación | |
| Permite desarrollo y testing sin infraestructura de messaging | |

## Por todo esto, definimos

Definir una **interfaz `EventPublisher`** desacoplada del transporte, con dos implementaciones:

1. **`LoggingEventPublisher` (placeholder, default)**: serializa los eventos a JSON y los registra en logs. Es el modo activo por defecto (`eda.publisher: logging`).
2. **`EdaHttpEventPublisher` (HTTP, opt-in)**: publica por POST al event-gateway del Grupo 1 cuando `eda.publisher: http` y `EDA_GATEWAY_URL` está configurada. Obtiene un token de servicio vía `client_credentials` contra `/oauth/token` (con caché en memoria hasta 30 s antes de expirar) y envía el envelope `EdaEventEnvelope` con `Authorization: Bearer`.

La "migración futura" ya no es solo Kafka/RabbitMQ: es el **event-gateway HTTP** del Grupo 1. El path de publicación sigue configurable (`eda.event-path`, default `/api/v1/events`) porque el contrato definitivo del Grupo 1 aún no cierra (ver `Guia_EDA.md` y `Contrato_Eda.md`).

Razones principales:
1. **Independencia**: Desarrollamos y testeamos sin esperar al Grupo 1 (y seguimos pudiendo hacerlo con el default de logging)
2. **Desacoplamiento**: La interfaz `EventPublisher` no cambia — solo se reemplaza/selecciona la implementación
3. **Testing**: Los tests verifican la publicación contra un servidor HTTP mockeado, sin broker ni gateway real
4. **Progresividad**: El transporte HTTP ya existe; cuando el Grupo 1 confirme el contrato final solo hay que fijar la configuración, no reescribir el dominio

## Consecuencias

### Positivas

- Desarrollo y testing inmediatos, sin infraestructura de messaging
- Dominio desacoplado de la tecnología de transporte
- Selección de implementación por ambiente vía configuración (`eda.publisher`)
- El transporte HTTP real ya está implementado y testeado: el cierre depende solo del contrato externo

### Negativas

- Con el default (logging) no hay entrega real ni durabilidad
- El publisher HTTP **no tiene reintentos ni timeouts configurados**: un gateway colgado puede colgar el request que publica (pendiente: backoff + timeouts + criterio de entrega)
- Dependemos de decisiones del Grupo 1 para cerrar la integración (endpoint definitivo, envelope final, URL alcanzable desde Docker)

## Referencias (benchmark)

- Gregor Hohpe & Bobby Woolf — Enterprise Integration Patterns (Addison-Wesley) — https://www.enterpriseintegrationpatterns.com/
- Martin Fowler — What do you mean by “Event-Driven”? — https://martinfowler.com/articles/201701-event-driven.html
- Spring Cloud Stream (abstracción sobre brokers de mensajería) — https://spring.io/projects/spring-cloud-stream
- Apache Kafka Documentation — https://kafka.apache.org/documentation/
- RabbitMQ Tutorials — https://www.rabbitmq.com/tutorials
