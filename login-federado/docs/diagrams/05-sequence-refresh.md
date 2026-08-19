# Sequence Diagram — Flujo de Refresh Token

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (App)
    participant AC as AuthController
    participant AS as AuthService
    participant RTS as RefreshTokenService
    participant DB as PostgreSQL
    participant JWT as JwtEncoder

    C->>AC: POST /auth/refresh {refreshToken}
    AC->>AS: refresh(request)

    AS->>RTS: validateAndRotate(rawToken)
    RTS->>DB: findByTokenHash(SHA-256(rawToken))
    DB-->>RTS: RefreshToken entity

    alt token no encontrado
        RTS-->>AS: BadCredentialsException
        AS-->>AC: 401 Unauthorized
    else token revocado o expirado
        RTS-->>AS: BadCredentialsException
        AS-->>AC: 401 Unauthorized
    end

    rect rgb(255, 245, 230)
        Note over RTS,DB: Rotación de token (uso único)
        RTS->>RTS: revoke() — marca como revocado
        RTS->>DB: UPDATE refresh_tokens SET revoked=true
        RTS-->>AS: RefreshTokenPrincipal (user data)
    end

    rect rgb(240, 240, 255)
        Note over AS,JWT: Nuevos tokens
        AS->>JWT: encode(nuevo access token, mismos claims)
        JWT-->>AS: newAccessToken

        AS->>RTS: issueFor(username, nombre, email, roles)
        RTS->>DB: INSERT nuevo refresh_tokens
        RTS-->>AS: newRefreshToken
    end

    AS-->>AC: LoginResponse {newAccessToken, newRefreshToken}
    AC-->>C: 200 OK + nuevos tokens
```

## Resumen

1. **Validación**: Se busca el hash del token en PostgreSQL
2. **Rotación**: El token viejo se revoca INMEDIATAMENTE (uso único)
3. **Nuevos tokens**: Se emite un nuevo access token + un nuevo refresh token
4. **Seguridad**: Si alguien reutiliza un token ya usado, falla (detecta robo de tokens)
