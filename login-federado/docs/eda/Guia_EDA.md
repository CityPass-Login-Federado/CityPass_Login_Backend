# Guía de integración EDA

**Última revisión:** 19 de noviembre de 2026

## Autenticación del productor

El publisher obtiene un token OAuth 2.0 con:

```http
POST /oauth/token
Authorization: Basic base64(grupo2:secret)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

El JWT de servicio incluye `sub=grupo2`, `namespace=com.citypass.auth`,
`aud` con `citypass`, `token_use=service`, `ver=1`, `jti`, `iat` y
`exp`.

## Publicación

La aplicación no publica dentro del request de login. Inserta en
`event_outbox` y el relay:

1. reclama un lote vencido con `SKIP LOCKED`;
2. reserva temporalmente las filas;
3. obtiene o reutiliza un token de servicio;
4. envía el envelope al Gateway;
5. marca `SENT` o reprograma con backoff;
6. marca `FAILED` al agotar los intentos.

## Configuración local

```text
EDA_PUBLISHER=logging
EDA_CLIENT_ID=grupo2
EDA_CLIENT_SECRET=grupo2-secret-dev
```

## Configuración integrada

```text
EDA_PUBLISHER=http
EDA_GATEWAY_URL=https://...
EDA_TOKEN_URL=https://...
EDA_EVENT_PATH=/api/v1/events
EDA_TOKEN_PATH=/oauth/token
EDA_CLIENT_ID=grupo2
EDA_CLIENT_SECRET=<secret administrado fuera del repositorio>
```

Validar el archivo `.env` resultante: `EDA_CLIENT_ID` debe contener `grupo2` y
`EDA_CLIENT_SECRET` el secreto. Al corte, los workflows CD no cumplen esa separación
(`CD.yml` asigna el secreto al id y `manual-deploy.yml` referencia además
`SVC_GROUPO2_SECRET`), por lo que la configuración generada debe corregirse antes de
probar la salida real.

Los timeouts efectivos actuales son los defaults de `EdaProperties` (3 s de conexión
y 5 s de lectura). Las claves `*-timeout-millis` de `application.yml` no enlazan esos
campos `Duration`; no presentar las variables `EDA_*_TIMEOUT_MILLIS` como configurables
hasta corregir el binding.

## Verificación

```sql
SELECT id, event_type, status, attempts, next_attempt_at, sent_at
FROM event_outbox
ORDER BY created_at DESC
LIMIT 20;
```

Para probar la integración:

1. ejecutar login, refresh y logout;
2. comprobar las tres filas;
3. correlacionar `id` con `authEventId` en el Gateway;
4. simular una indisponibilidad y confirmar que la autenticación continúa;
5. restaurar el Gateway y observar la entrega reintentada.

## Operación

- Revisar periódicamente `PENDING` antiguos y `FAILED`.
- Corregir la causa antes de reencolar manualmente.
- No cambiar `id` ni payload durante un reintento.
- Definir una política de retención para `SENT` antes de que el volumen sea
  significativo.

Consultar también ADR-010, `10-sequence-metricas.md` y
`demo-hito-2.md`.
