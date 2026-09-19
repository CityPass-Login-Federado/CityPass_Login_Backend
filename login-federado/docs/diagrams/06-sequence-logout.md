# Secuencia — Logout por refresh token

**Estado representado:** AS-IS del endpoint `POST /auth/logout`.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente web/móvil
    participant AC as AuthController
    participant AS as AuthService
    participant RTS as RefreshTokenService
    participant DB as PostgreSQL

    Note over C,AC: /auth/** es público y no requiere access token
    C->>AC: POST /auth/logout {refreshToken}
    AC->>AS: logout(rawToken)
    AS->>RTS: revokeSingle(rawToken)

    alt token nulo o vacío
        RTS-->>AS: Sin cambios
    else token informado
        RTS->>RTS: SHA-256(rawToken)
        RTS->>DB: findByTokenHash(hash)
        DB-->>RTS: token o vacío
        opt token conocido
            RTS->>DB: Guarda revoked_at = now
        end
    end

    RTS-->>AS: void
    AS-->>AC: void
    AC-->>C: 204 No Content
```

## Semántica de seguridad

1. El logout recibe el refresh token en el body; no identifica al usuario desde un JWT.
2. Solo revoca el refresh token presentado. Otros refresh tokens de la misma persona permanecen activos.
3. Un token desconocido devuelve también 204 para no revelar su existencia.
4. Los access tokens ya emitidos siguen válidos hasta su expiración máxima de 15 minutos.
5. La revocación masiva por `sub` es otra operación y se usa al deshabilitar una persona desde el panel.
