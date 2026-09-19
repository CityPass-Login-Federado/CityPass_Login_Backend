# Secuencia — Login humano

**Estado representado:** AS-IS del endpoint `POST /auth/login`.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente web/móvil
    participant AC as AuthController
    participant AS as AuthService
    participant CR as ClientRegistry
    participant LA as LoginAttemptService
    participant LD as LdapDirectory
    participant LDAP as OpenLDAP
    participant ARC as AnomalyRiskClient
    participant AD as anomaly-detection
    participant ATI as AccessTokenIssuer
    participant RTS as RefreshTokenService
    participant DB as PostgreSQL
    participant EP as LoggingEventPublisher

    C->>AC: POST /auth/login {username, password, clientId}
    AC->>AC: Resuelve IP y User-Agent
    AC->>AS: login(request, ip, userAgent)

    AS->>CR: requireHuman(clientId)
    alt cliente inexistente o de servicio
        CR-->>C: 401 genérico
    end

    AS->>LA: assertNotLocked(username)
    LA->>DB: COUNT fallidos desde ahora - 15 min
    DB-->>LA: cantidad
    alt cantidad >= 5
        LA-->>AS: AccountLockedException
        AS-->>C: 401 genérico
    end

    AS->>LD: findByUid(username)
    LD->>LDAP: Search global uid + atributos operacionales
    LDAP-->>LD: persona o vacío
    alt persona inexistente, deshabilitada o sin employeeNumber
        LD->>LDAP: dummyBind(password)
        AS->>LA: recordAttempt(..., false)
        LA->>DB: INSERT login_attempts
        AS-->>C: 401 genérico
    end

    AS->>CR: acceptsModule(client, person.module)
    alt módulo incompatible con el clientId
        AS->>LA: recordAttempt(..., false)
        LA->>DB: INSERT login_attempts
        AS-->>C: 401 genérico
    end

    AS->>LD: bind(person.dn, password)
    LD->>LDAP: LDAP bind con DN exacto
    alt contraseña inválida o error LDAP
        AS->>LA: recordAttempt(..., false)
        LA->>DB: INSERT login_attempts
        AS-->>C: 401 genérico
    end

    AS->>ARC: score(username, ip, userAgent)
    ARC->>AD: POST /score
    AD->>DB: Consulta historial de login
    DB-->>AD: Features históricas
    AD-->>ARC: {risk_score, decision, reasons}
    alt anomaly-detection no responde
        ARC-->>AS: AnomalyServiceUnavailableException
        AS-->>C: 401 genérico (fail-closed)
    else decision = BLOCK
        AS->>LA: recordAttempt(..., false)
        LA->>DB: INSERT login_attempts
        AS-->>C: 401 genérico
    end

    AS->>LA: recordAttempt(..., true)
    LA->>DB: INSERT login_attempts

    AS->>ATI: issueHuman(person, client)
    Note over ATI: Claims humanos: sub, aud, token_use, ver, preferred_username, module y groups
    Note over ATI: Claims estándar: iss, iat, exp y jti
    ATI-->>AS: accessToken RS256 (15 min)

    AS->>RTS: issueInitial(person, client)
    RTS->>DB: INSERT refresh_tokens con hash, chainId, sub, clientId y audience
    RTS-->>AS: refreshToken opaco de 64 bytes (8 h)

    AS->>EP: publish(usuario.autenticado, evento)
    EP->>EP: Serializa el evento en el log
    Note over EP: No existe todavía un broker Kafka/RabbitMQ

    AS-->>AC: LoginResponse con access_token, refresh_token, token_type y expires_in
    AC-->>C: 200 OK
```

## Reglas reflejadas

1. El `clientId` es obligatorio y limita el módulo y la audience del JWT.
2. Toda falla de autenticación, lockout o anomalías devuelve el mismo 401 para evitar enumeración.
3. La evaluación de anomalías ocurre después de un bind LDAP exitoso y antes de emitir tokens.
4. El access token dura 15 minutos; el refresh token dura 8 horas y solo se almacena hasheado.
5. La publicación de eventos es actualmente un placeholder basado en logs.
