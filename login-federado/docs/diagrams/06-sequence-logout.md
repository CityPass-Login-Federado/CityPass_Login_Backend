# Secuencia — Cierre de sesión

**Fecha de revisión:** 19 de noviembre de 2026
**Endpoint:** `POST /auth/logout`

El cierre de sesión recibe el refresh token en el cuerpo. El access token es opcional: si se envía como Bearer, su `jti` también queda revocado. La operación es idempotente hacia el cliente.

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant C as Cliente del módulo
    participant AC as AuthController
    participant AS as AuthService
    participant RT as RefreshTokenService
    participant DB as PostgreSQL
    participant RV as TokenRevocationService
    participant OB as EventOutboxService

    U->>C: Cierra la sesión
    C->>AC: POST /auth/logout<br/>{refreshToken}<br/>Bearer access opcional
    AC->>AS: logout(refreshToken, bearerOpcional)
    alt Refresh token desconocido
        AS->>RT: revokeSingle(refreshToken)
        RT-->>AS: Sin sesión reconocida
    else Refresh token conocido
        AS->>RT: revokeSingle(refreshToken)
        RT->>DB: Marcar solamente ese token como revocado
        RT->>OB: Encolar identidad.logout
        OB->>DB: INSERT event_outbox (PENDING)
    end
    opt Bearer access token parseable y no vencido
        AS->>RV: Revocar jti hasta exp
        RV->>DB: UPSERT revoked_access_tokens
    end
    AS-->>AC: Operación completada
    AC-->>C: 204 No Content
```

## Alcance de la revocación

- El endpoint revoca únicamente el refresh token presentado y no toda la cadena.
- Repetir la solicitud o enviar un token desconocido mantiene la respuesta `204 No Content`.
- Si el cliente omite el Bearer token, ese access token puede seguir válido hasta expirar; si lo presenta, este backend lo rechaza por denylist. Una API externa que solo valida JWKS no consulta esa tabla.
- Los cambios o recuperos de contraseña revocan refresh tokens y avanzan `revoked_subject_cutoffs`, que este backend consulta al validar access tokens. La deshabilitación administrativa hoy revoca los refresh tokens, pero no avanza ese corte.
