# Metricas de autenticacion

El backend publica un evento crudo por cada operacion exitosa de autenticacion:

- `identidad.login`: login LDAP exitoso que emite una sesion.
- `identidad.refresh`: refresh token validado y rotado correctamente.
- `identidad.logout`: refresh token revocado, incluido el logout masivo por baja de una persona.

Todos los eventos usan `RawAuthenticationEvent` y contienen:

- `eventId`, `eventType` y `occurredAt` en GMT 0/UTC. `occurredAt` se serializa
	con el sufijo `Z`.
- `userSub`, el `employeeNumber` estable de la persona.
- `username` cuando el flujo lo conoce; logout puede dejarlo nulo.
- `department`, que corresponde al modulo/OU de LDAP.
- `clientId` y `chainId` cuando aplican.
- `ipAddress` y `userAgent` recibidos por HTTP cuando estan disponibles.

El evento nunca incluye el refresh token crudo ni su hash. Se publica despues de
que la operacion principal termino correctamente. Un login rechazado, un refresh
invalido o un logout de un token desconocido no generan evento.

El backend no calcula DAU, MAU, distribuciones horarias, duraciones de sesion ni
promedios. El equipo de Metricas debe consumir estos hechos, deduplicar por
`eventId` si el bus reentrega mensajes y realizar sus propias agregaciones. La
hora del evento es `occurredAt` en GMT 0/UTC; para agrupaciones diarias o
horarias se debe convertir a la zona horaria elegida por Analitica.