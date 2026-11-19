# ADR-006: Event bus — placeholder con abstracción para Kafka/RabbitMQ

La entrega directa de eventos descrita en este ADR fue reemplazada por el [ADR-010](ADR-010-outbox-eventos-eda.md).

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

La evolución posterior adoptó el **event-gateway HTTP** del Grupo 1 y el patrón
Outbox descrito en ADR-010. El contrato y la guía vigentes están en
`docs/eda/Contrato_Eda.md` y `docs/eda/Guia_EDA.md`. Este ADR se conserva como
registro histórico y no debe utilizarse para describir la entrega actual.

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
- Esta consecuencia pertenecía a la publicación directa original. La implementación vigente usa Outbox, reintentos y timeouts según ADR-010. Al corte persiste una observación de configuración: `application.yml` declara claves `*-timeout-millis` que no enlazan los campos `Duration` de `EdaProperties`, por lo que se aplican sus defaults de 3 s y 5 s.
- Dependemos de decisiones del Grupo 1 para cerrar la integración (endpoint definitivo, envelope final, URL alcanzable desde Docker)

## Referencias (benchmark)

- Gregor Hohpe & Bobby Woolf — Enterprise Integration Patterns (Addison-Wesley) — https://www.enterpriseintegrationpatterns.com/
- Martin Fowler — What do you mean by “Event-Driven”? — https://martinfowler.com/articles/201701-event-driven.html
- Spring Cloud Stream (abstracción sobre brokers de mensajería) — https://spring.io/projects/spring-cloud-stream
- Apache Kafka Documentation — https://kafka.apache.org/documentation/
- RabbitMQ Tutorials — https://www.rabbitmq.com/tutorials
