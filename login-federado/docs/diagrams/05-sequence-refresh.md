# Sequence Diagram — Rotación de Refresh Token

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant AC as AuthController
    participant AS as AuthService
    participant RTS as RefreshTokenService
    participant DB as PostgreSQL
    participant LDAP as OpenLDAP
    participant JWT as AccessTokenIssuer

    C->>AC: POST /auth/refresh {refreshToken}
    AC->>AS: refresh(request)
    AS->>RTS: continueChain(rawToken)
    RTS->>DB: findByTokenHash(SHA-256(rawToken))

    alt desconocido o expirado
        RTS-->>C: 401 genérico
    else ya revocado: posible robo
        RTS->>DB: revocar toda chain_id
        RTS-->>C: 401 genérico
    else token usable
        RTS->>DB: guardar revoked_at del eslabón actual
        RTS->>LDAP: reloadBySub(sub)
        LDAP-->>RTS: persona y grupos actuales
        RTS-->>AS: persona, chain_id y cliente
        AS->>JWT: issueHuman(persona, cliente)
        AS->>RTS: issueNext(persona, chain_id, cliente)
        RTS->>DB: INSERT siguiente refresh token
        AS-->>C: 200 + nuevo access y refresh
    end
```

El refresh vive ocho horas, rota en cada uso y solo se persiste como hash. La relectura LDAP evita renovar permisos obsoletos o cuentas deshabilitadas.
