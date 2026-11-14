# ADR-010: Outbox para los eventos de autenticación hacia EDA

## Quiénes

| Nombre | Rol |
|--------|-----|
| Federico Abeledo | PM (autor) |
| Nicolás Hernández | revisor |

## Consideraciones

El login, el refresh y el logout publicaban el evento en el mismo request, después de emitir los tokens. `EdaHttpEventPublisher` pide un token `client_credentials` y hace un POST al Event Gateway. Si EDA o su endpoint de token no responden, la excepción sube y el cliente recibe 500 aunque el token ya se haya generado. Todos los grupos se autentican por este módulo: un gateway caído no puede dejar a nadie afuera.

El id del evento tiene que ser estable entre reintentos. EDA tiene que poder descartar un duplicado si el mismo hecho llega dos veces.

Restricciones:

- El schema se aplica en cada arranque (`sql.init.mode=always`) y no puede romper una base que ya existe.
- En local, `eda.publisher=logging` tiene que seguir funcionando sin gateway.
- El POST a EDA tiene timeout (connect 3s, read 5s). La reserva de la fila tiene que durar más que esas dos llamadas juntas.

### Opciones consideradas

#### Opción A: Publicación directa en el request

| Pros | Contras |
|------|---------|
| Simple: un POST y listo | Si EDA no responde, el login devuelve 500 |
| El evento sale al instante | No hay reintentos ni durabilidad |
| | Un gateway colgado retiene el thread del login |

#### Opción B: try/catch sin reintentos

| Pros | Contras |
|------|---------|
| El login deja de depender de EDA | Si EDA estaba caído en ese momento, el evento se pierde |
| Cambio chico | No hay forma de reenviar lo que falló |

#### Opción C: Cola en memoria

| Pros | Contras |
|------|---------|
| No toca la base | Un reinicio del proceso borra lo que no se llegó a mandar |
| El request no espera a EDA | Con más de una instancia, cada una tiene su cola |
| | Un pico de eventos puede llenar la memoria |

#### Opción D: Outbox en la misma base

| Pros | Contras |
|------|---------|
| El login solo inserta una fila; EDA no está en el camino | El evento sale segundos después, no en el acto |
| Sobrevive a un reinicio | Puede llegar más de una vez |
| Reintenta con espera creciente | El orden entre eventos no está garantizado |
| El id de la fila viaja en el payload y EDA puede descartar duplicados | Hay que mirar las filas que quedan en FAILED |

## Por todo esto, definimos

Guardar cada evento de autenticación en la tabla `event_outbox` y mandarlo después, con un relay.

1. Login, refresh y logout insertan una fila `PENDING`. El `id` de la fila es el `eventId` del `RawAuthenticationEvent`, que el publisher ya manda como `authEventId`. Si el insert falla, se loguea y el request sigue.
2. Un relay con `@Scheduled` toma un lote de pendientes vencidos con `SELECT … FOR UPDATE SKIP LOCKED`. Antes de llamar a EDA corre `next_attempt_at` para reservar la fila y suelta la transacción: el POST no deja una conexión de Postgres tomada.
3. El envío usa el `EventPublisher` que ya elige la config. Con `eda.publisher=http` es `EdaHttpEventPublisher` (con connect timeout y read timeout). Con `eda.publisher=logging` el relay escribe el log y marca la fila `SENT`.
4. Si el envío sale bien, la fila pasa a `SENT`. Si falla, suma un intento y reprograma con espera que se duplica hasta un tope. Un 429 espera al menos lo que dice `Retry-After` cuando eso es más largo que la espera calculada. Un 504 se reintenta con la espera normal: el payload y el `authEventId` no cambian, y EDA descarta el duplicado por `payloadHash`. Al llegar al máximo de intentos pasa a `FAILED` y no se reintenta más.
5. El intervalo, el tamaño del lote, el máximo de intentos, la espera y la reserva son propiedades (`eda.outbox.*`), con defaults: cada 5 segundos, lotes de 20, 20 intentos, espera de 5s a 30 min, reserva de 30s. Esos 20 intentos cubren unas 6 horas de EDA caído. La reserva tiene que ser mayor que dos veces connect timeout más read timeout, porque cada envío pide token y después hace el POST; si no, la aplicación no arranca.

## Consecuencias

### Positivas

- Un EDA caído no corta el login, el refresh ni el logout.
- Lo que no se pudo mandar queda en la base y se reintenta solo, también después de un reinicio.
- El mismo id sale en cada reintento, así EDA puede descartar el duplicado.
- Dos instancias pueden correr el relay a la vez sin mandar la misma fila en el mismo instante (`SKIP LOCKED` más la reserva).
- El modo `logging` sigue sirviendo para desarrollar sin gateway.

### Negativas

- Los eventos pueden llegar más de una vez (se cayó el proceso después del POST y antes de marcar `SENT`, o la reserva venció mientras el envío seguía).
- El orden no está garantizado: un evento más nuevo puede salir antes que uno que venía reintentando.
- Hay una demora de segundos entre el login y la entrega.
- Una fila `FAILED` no se reintenta sola: hay que mirarla y decidir qué hacer.
- La tabla crece con los `SENT`. Este ADR no define una limpieza.

## Referencias (benchmark)

- Chris Richardson — Pattern: Transactional outbox — https://microservices.io/patterns/data/transactional-outbox.html
- Martin Fowler — What do you mean by “Event-Driven”? — https://martinfowler.com/articles/201701-event-driven.html
- PostgreSQL — SELECT … FOR UPDATE SKIP LOCKED — https://www.postgresql.org/docs/current/sql-select.html
- Gregor Hohpe & Bobby Woolf — Enterprise Integration Patterns — https://www.enterpriseintegrationpatterns.com/
