# Sequence Diagram — Flujo de Login

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (App)
    participant AC as AuthController
    participant AS as AuthService
    participant LA as LoginAttemptService
    participant LDAP as OpenLDAP
    participant JWT as JwtEncoder
    participant RTS as RefreshTokenService
    participant DB as PostgreSQL
    participant EP as EventPublisher

    C->>AC: POST /auth/login {username, password}
    AC->>AS: login(request, ip, userAgent)

    rect rgb(255, 240, 240)
        Note over AS,LA: Capa 1 — Anti-brute-force
        AS->>LA: assertNotLocked(username)
        LA->>DB: COUNT intentos fallidos (últimos 15 min)
        DB-->>LA: count
        alt count >= 5
            LA-->>AS: AccountLockedException (423)
            AS-->>AC: Error: cuenta bloqueada
            AC-->>C: 423 Locked
        end
    end

    rect rgb(240, 255, 240)
        Note over AS,LDAP: Autenticación LDAP
        AS->>LDAP: bind(username, password)
        LDAP-->>AS: LdapUserPrincipal (username, nombre, email, roles)
    end

    rect rgb(240, 240, 255)
        Note over AS,EP: Emisión de tokens + evento
        AS->>LA: recordAttempt(username, ip, ua, true)
        LA->>DB: INSERT login_attempts

        AS->>JWT: encode(RS256, claims: sub, roles, name, email)
        JWT-->>AS: accessToken (JWT)

        AS->>RTS: issueFor(username, nombre, email, roles)
        RTS->>DB: INSERT refresh_tokens (hash SHA-256)
        RTS-->>AS: refreshToken (raw, solo se muestra 1 vez)

        AS->>EP: publish("usuario.autenticado", event)
    end

    AS-->>AC: LoginResponse {accessToken, refreshToken, "Bearer", 900}
    AC-->>C: 200 OK + tokens
```

## Resumen

1. **Anti-brute-force**: Se verifica antes de tocar LDAP (capa de protección barata)
2. **LDAP bind**: Autenticación real contra el directorio
3. **JWT RS256**: Access token de 15 min con claims custom (roles, name, email)
4. **Refresh token**: Token opaco de 64 bytes, solo se almacena su hash SHA-256 (7 días)
5. **Evento**: Se publica `usuario.autenticado` para el bus de eventos
