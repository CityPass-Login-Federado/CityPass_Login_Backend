# Contrato de eventos de identidad

**Versión documental:** 1
**Última revisión:** 19 de noviembre de 2026

## Eventos publicados

| Tipo EDA | Schema versionado | Momento de creación |
|---|---|---|
| `identidad.login` | `LoginExitoso.json` | Después de un login LDAP exitoso |
| `identidad.refresh` | `TokenRenovado.json` | Después de rotar correctamente el refresh token |
| `identidad.logout` | `SesionCerrada.json` | Después de cerrar una sesión reconocida |

## Campos

| Campo | Obligatorio | Semántica |
|---|---:|---|
| `authEventId` | Sí | UUID estable; coincide con el id del Outbox y no cambia en reintentos |
| `actorSub` | Sí | Identificador estable de la persona |
| `occurredAt` | Sí | Instante del hecho en UTC |
| `username` | No | Nombre visible, si estaba disponible |
| `department` | No | Módulo/departamento de la persona |
| `clientId` | No | Cliente web que originó la sesión |
| `chainId` | No | Cadena de refresh tokens |
| `ipAddress` | No | IP observada por el IdP |
| `userAgent` | No | Agente informado por el cliente |

## Seguridad y privacidad

- El JWT humano no se reenvía al bus.
- El publisher se autentica con un JWT de servicio de `grupo2` y namespace
  `com.citypass.auth`.
- Nunca se incluyen contraseñas, access tokens, refresh tokens, hashes ni secretos.
- Los consumidores no deben usar `username` como clave estable; deben usar
  `actorSub`.
- El consumidor debe deduplicar por `authEventId`.

## Garantías

- Entrega al menos una vez.
- Identidad del evento estable entre reintentos.
- No se garantiza orden global.
- Un evento puede llegar duplicado.
- La indisponibilidad del Gateway no debe convertir un login exitoso en error.
