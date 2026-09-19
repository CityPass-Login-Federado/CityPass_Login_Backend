# Secuencia — Rotación de refresh token

**Estado representado:** AS-IS del endpoint `POST /auth/refresh`.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente web/móvil
    participant AC as AuthController
    participant AS as AuthService
    participant RTS as RefreshTokenService
    participant DB as PostgreSQL
    participant LD as LdapDirectory
    participant LDAP as OpenLDAP
    participant CR as ClientRegistry
    participant ATI as AccessTokenIssuer

    C->>AC: POST /auth/refresh {refreshToken}
    AC->>AS: refresh(request)
    AS->>RTS: continueChain(rawToken)

    RTS->>RTS: SHA-256(rawToken)
    RTS->>DB: findByTokenHash(hash)
    DB-->>RTS: RefreshToken o vacío

    alt token inexistente
        RTS-->>C: 401 genérico
    else token ya revocado (reuso)
        RTS->>DB: revokeChain(chainId, now)
        RTS-->>C: 401 genérico con cadena completa revocada
    else token expirado
        RTS-->>C: 401 genérico
    else token activo
        RTS->>DB: UPDATE condicional revokeIfActive(hash, now)
        alt otra solicitud lo revocó primero
            RTS->>DB: revokeChain(chainId, now)
            RTS-->>C: 401 genérico por reuso concurrente
        else revocación exitosa
            RTS->>DB: Guarda el eslabón revocado
        end
    end

    RTS->>LD: reloadBySub(stored.sub)
    LD->>LDAP: Search por employeeNumber con estado, módulo y memberOf actuales
    LDAP-->>LD: persona revalidada o vacío
    alt persona borrada o deshabilitada
        RTS-->>C: 401 genérico
    end

    RTS->>CR: requireHuman(stored.clientId)
    RTS->>CR: valida audience y acceptsModule(...)
    alt cliente, audience o módulo incompatibles
        RTS-->>C: 401 genérico
    end

    RTS-->>AS: ChainContinuation(person actual, chainId, client)
    AS->>ATI: issueHuman(person actual, client)
    ATI-->>AS: Nuevo access token con grupos y módulo actuales

    AS->>RTS: issueNext(person, mismo chainId, client)
    RTS->>DB: INSERT siguiente eslabón refresh_tokens
    RTS-->>AS: Nuevo refresh token opaco

    AS-->>AC: LoginResponse con access_token, refresh_token, token_type y expires_in
    AC-->>C: 200 OK
```

## Reglas reflejadas

1. Cada refresh token válido se usa una sola vez.
2. El reuso, incluido el canje concurrente, revoca toda la cadena como señal de posible robo.
3. La cuenta, los grupos y el módulo se releen desde LDAP en cada canje.
4. Los claims no se copian del token anterior: se emiten con el estado actual del directorio.
5. El siguiente refresh token conserva el `chainId`, `clientId` y audience de la sesión.
