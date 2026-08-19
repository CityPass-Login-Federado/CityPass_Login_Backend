# Sequence Diagram — Flujo de Logout

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (App)
    participant AC as AuthController
    participant SEC as SecurityFilterChain
    participant AS as AuthService
    participant RTS as RefreshTokenService
    participant DB as PostgreSQL

    C->>AC: POST /auth/logout
    Note over C,AC: Header: Authorization: Bearer <accessToken>

    rect rgb(255, 240, 240)
        Note over SEC: Validación JWT (obligatorio)
        SEC->>SEC: JwtDecoder valida firma RS256 + expiración
        SEC->>SEC: Extrae subject del JWT
    end

    AC->>AS: logout(username = jwt.getSubject())
    AS->>RTS: revokeAllFor(username)
    RTS->>DB: findAllByUsernameAndRevokedFalse(username)
    DB-->>RTS: lista de tokens activos

    loop Para cada token activo
        RTS->>RTS: revoke()
    end

    RTS->>DB: UPDATE refresh_tokens SET revoked=true WHERE...
    RTS-->>AS: void
    AS-->>AC: void
    AC-->>C: 204 No Content
```

## Resumen

1. **JWT obligatorio**: El endpoint `/auth/logout` NO es público — requiere un access token válido
2. **Identificación**: El `sub` del JWT identifica al usuario (no se recibe por body)
3. **Revocación masiva**: Se revocan TODOS los refresh tokens activos del usuario
4. **Access token**: Sigue válido hasta su expiración (15 min) — no hay blacklist
