# Sequence Diagram — Logout

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant AC as AuthController
    participant AS as AuthService
    participant RTS as RefreshTokenService
    participant DB as PostgreSQL

    C->>AC: POST /auth/logout {refreshToken}
    Note over C,AC: Endpoint público; no exige access token
    AC->>AS: logout(rawToken)
    AS->>RTS: revokeSingle(rawToken)
    RTS->>DB: buscar SHA-256(rawToken)
    alt token conocido
        RTS->>DB: guardar revoked_at
    else token desconocido o vacío
        Note over RTS: No revela si existió
    end
    AC-->>C: 204 No Content
```

El logout revoca solo el refresh presentado. El access token existente continúa válido hasta su expiración máxima de 15 minutos. La revocación masiva por `sub` se usa al deshabilitar una persona desde el panel, no en este endpoint.
