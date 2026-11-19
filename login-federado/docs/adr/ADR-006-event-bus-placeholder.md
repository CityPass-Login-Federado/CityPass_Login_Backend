# ADR-006: Event bus — placeholder con abstracción para Kafka/RabbitMQ

La decisión de aislar el transporte detrás de `EventPublisher` sigue vigente. La entrega directa desde el request fue reemplazada por el outbox del [ADR-010](ADR-010-outbox-eventos-eda.md).

## Quiénes

| Nombre | Rol |
|--------|-----|
| Antonio Wu | Seguridad / Backend |

## Consideraciones

CityPass+ requiere comunicación asincrónica entre módulos (EDA — Event Driven Architecture). El Grupo 1 diseña el bus de eventos, contratos y políticas. El módulo de login necesita:
- Publicar eventos de autenticación (login, renovación de token y cierre de sesión)
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

1. **`LoggingEventPublisher`** es la implementación predeterminada (`EDA_PUBLISHER=logging`). Serializa el evento con su envelope y lo escribe en el log.
2. **`EdaHttpEventPublisher`** se selecciona con `EDA_PUBLISHER=http` y requiere `EDA_GATEWAY_URL` para entregar eventos. Obtiene un token mediante `client_credentials` (por defecto en `/oauth/token`) y lo reutiliza hasta 30 s antes de su vencimiento. Envía los datos del evento con autenticación Bearer a `POST /api/v1/event-types/{fqn}/events`. Para un login, el tipo es `com.citypass.auth.LoginExitoso`. El gateway agrega los metadatos del envelope.

### Cuándo se publica un login

`AuthService.login` emite los tokens y llama a `EventOutboxService.enqueue`, que inserta el evento en `event_outbox` con estado `PENDING` en una transacción propia. El request espera esa operación de base de datos. Si la inserción falla, `AuthService` registra el error y devuelve los tokens igualmente.

`EventOutboxRelay` consulta las filas pendientes mediante una tarea programada con una pausa predeterminada de 5 s entre ejecuciones. Es el único componente que llama a `EventPublisher.publish`. Tanto la obtención del token de servicio como el POST al gateway ocurren en esa tarea. El login puede responder mientras EDA sigue sin contestar.

## Consecuencias

### Positivas

- Desarrollo y testing inmediatos, sin infraestructura de messaging
- Dominio desacoplado de la tecnología de transporte
- Selección de implementación por ambiente vía configuración (`eda.publisher`)
- El relay marca la fila como `SENT` cuando el publisher termina correctamente. En modo HTTP, un fallo de entrega se reintenta con espera creciente según el ADR-010.

### Negativas

- En modo `logging`, una fila `SENT` indica que el relay procesó el evento localmente. Para entregarlo al gateway se requiere el modo HTTP.
- Si falla la inserción en el outbox, el login conserva sus tokens y el evento se pierde. Si el evento quedó guardado y se agotan los intentos de entrega, la fila pasa a `FAILED`.

## Verificación local del 19 de noviembre de 2026

Desde `login-federado`, las tres suites pasaron en la revisión `7c6d459` (36 pruebas, cero fallos, errores u omisiones).

```sh
mvn "-Dtest=AuthServiceTest,EventOutboxRelayTest,EdaHttpEventPublisherTest" test
```

- `AuthServiceTest` comprobó que el login devuelve ambos tokens al encolar el evento y también cuando el outbox rechaza la escritura.
- `EventOutboxRelayTest` comprobó que una publicación exitosa marca el evento como `SENT`, que un 504 deja la fila pendiente para otro intento y que un 429 respeta `Retry-After`.
- `EdaHttpEventPublisherTest` comprobó el POST de login al endpoint correspondiente y el vencimiento del tiempo de espera cuando un servidor local acepta la conexión pero no responde a la solicitud de token.

## Referencias (benchmark)

- Gregor Hohpe & Bobby Woolf — Enterprise Integration Patterns (Addison-Wesley) — https://www.enterpriseintegrationpatterns.com/
- Martin Fowler — What do you mean by “Event-Driven”? — https://martinfowler.com/articles/201701-event-driven.html
- Spring Cloud Stream (abstracción sobre brokers de mensajería) — https://spring.io/projects/spring-cloud-stream
- Apache Kafka Documentation — https://kafka.apache.org/documentation/
- RabbitMQ Tutorials — https://www.rabbitmq.com/tutorials
