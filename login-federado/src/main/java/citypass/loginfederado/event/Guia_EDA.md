# Cumplimiento del contrato EDA

## Cambios realizados

- Se agrego el cliente de servicio `grupo2` con namespace `com.citypass.auth`.
- Los tokens de servicio ahora incluyen `sub`, `namespace`, `aud: ["citypass"]`, `iss`, `token_use: service`, `ver`, `jti`, `iat` y `exp`.
- La expiracion de los tokens de servicio quedo en 15 minutos.
- `POST /oauth/token` acepta credenciales por `Authorization: Basic` y por formulario (`client_id` y `client_secret`).
- Los errores OAuth usan el formato del contrato: `invalid_request`, `invalid_client` y `unsupported_grant_type`.
- Se agrego `GET /health` como endpoint publico.
- El JWKS existente mantiene el `kid` coincidente con los tokens y las claves persisten en `keys/` durante el desarrollo.
- Los eventos de login, refresh y logout se envuelven en un envelope EDA con `type`, `version`, `occurredAt`, `metadata` y `data`.
- La identidad humana viaja como `data.actorSub`; el token humano nunca se reenvia al bus.
- `metadata.source`, `metadata.namespace` y `metadata.tokenId` representan al productor de servicio cuando se usa el publisher HTTP.
- Se agrego un publisher HTTP opcional. Se activa configurando `EDA_GATEWAY_URL`; obtiene un token propio de `grupo2` desde `EDA_TOKEN_URL` y envia el envelope al endpoint configurado.
- Sin `EDA_GATEWAY_URL`, el publisher local sigue escribiendo el envelope completo en los logs para poder probar login, refresh y logout sin un broker.

## Configuracion del publisher real

```text
EDA_PUBLISHER=http
EDA_GATEWAY_URL=https://event-gateway.example
EDA_TOKEN_URL=https://identidad.example
EDA_EVENT_PATH=/api/v1/events
EDA_TOKEN_PATH=/oauth/token
EDA_CLIENT_ID=grupo2
EDA_CLIENT_SECRET=<secreto-de-grupo2>
```

El path `/api/v1/events` es configurable porque el documento recibido define el contrato de identidad y autenticacion, pero no incluye el path concreto de publicacion del gateway.

## Ejemplo de envelope

```json
{
  "type": "identidad.login",
  "version": 1,
  "occurredAt": "2026-09-16T12:00:00Z",
  "metadata": {
    "source": "grupo2",
    "namespace": "com.citypass.auth",
    "tokenId": "jti-del-token-de-servicio"
  },
  "data": {
    "actorSub": "U000001",
    "username": "jperez",
    "department": "reclamos",
    "clientId": "citypass-reclamos-web",
    "chainId": null,
    "ipAddress": "...",
    "userAgent": "..."
  }
}
```

## Verificacion local

Desde `login-federado/`:

```cmd
mvn test
```

Para levantar el entorno y revisar eventos sin gateway real:

```cmd
docker compose up --build -d
curl -i http://localhost:8081/health
curl -i -X POST http://localhost:8081/oauth/token -u "grupo2:grupo2-secret-dev" -d "grant_type=client_credentials"
docker compose logs -f app | findstr /i "EVENTO PUBLICADO"
```

El login exitoso publica `identidad.login`, el refresh exitoso publica `identidad.refresh` y el logout de un refresh valido publica `identidad.logout`. Los tres eventos usan `occurredAt` en UTC y terminan en `Z`.

## Pendiente externo

Este repositorio no contiene un broker Kafka ni el contrato definitivo del endpoint del event-gateway. Por eso la integracion HTTP queda configurable y el modo por defecto es logging. Para produccion, el Grupo 1 debe confirmar el path, envelope final, politicas de reintento, timeouts y la URL alcanzable desde Docker.
