# Secuencia — Inicio de sesión federado

**Fecha de revisión:** 19 de noviembre de 2026
**Endpoint:** `POST /auth/login`

El inicio de sesión humano exige `username`, `password` y `clientId`. Las fallas de identidad, contraseña, cliente, módulo y lockout comparten el mismo `401`; anomalías usa categorías explícitas `403`/`503` sin exponer puntaje, razones ni datos de la cuenta.

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant C as Cliente del módulo
    participant AC as AuthController
    participant AS as AuthService
    participant CR as ClientRegistry
    participant RL as LoginRateLimiter
    participant LA as LoginAttemptService
    participant LDAP as LDAP corporativo
    participant RA as Servicio de anomalías
    participant JWT as AccessTokenIssuer
    participant RT as RefreshTokenService
    participant DB as PostgreSQL
    participant AE as AnomalyBlockedEventService
    participant OB as EventOutboxService

    U->>C: Ingresa credenciales
    C->>AC: POST /auth/login<br/>{username, password, clientId}
    AC->>AS: authenticate(request)
    AS->>RL: Validar límite previo por IP
    AS->>CR: requireHumanClient(clientId)
    CR-->>AS: Cliente, audiencia y módulo
    AS->>LA: assertNotLocked(username)
    LA->>DB: Consultar intentos recientes
    alt Cinco fallos en 15 minutos
        LA-->>AS: Cuenta temporalmente bloqueada
        AS-->>AC: Error de autenticación
        AC-->>C: 401 Unauthorized genérico
    else Puede intentar autenticarse
        AS->>LDAP: Buscar identidad global<br/>y validar pertenencia al módulo
        AS->>LDAP: bind(username, password)
        alt Credenciales o pertenencia inválidas
            AS->>LA: recordFailure(username)
            LA->>DB: Registrar intento fallido
            AS-->>AC: Error genérico de autenticación
            AC-->>C: 401 Unauthorized
        else Identidad válida
            AS->>RA: POST /score con señales del intento
            alt Servicio de anomalías no disponible
                AS-->>AC: AnomalyServiceUnavailableException
                AC-->>C: 503 Service Unavailable
            else Decisión BLOCK sin aprobación consumible
                AS->>AE: Guardar evento BLOCKED y contexto
                AE->>DB: INSERT anomaly_blocked_events
                AS->>LA: recordFailure(username)
                LA->>DB: Registrar intento fallido
                AS-->>AC: AnomalyBlockedException
                AC-->>C: 403 Forbidden
            else Decisión ALLOW o REVIEW
                Note over AS,RA: REVIEW permite continuar,<br/>la decisión no se persiste actualmente
                AS->>LA: recordSuccess(username)
                LA->>DB: Limpiar estado de bloqueo
                AS->>JWT: Emitir access token humano RS256, 15 min
                Note over JWT: sub=employeeNumber, aud=cliente,<br/>token_use=human, ver, preferred_username,<br/>module y groups
                AS->>RT: createInitialToken(subject, client)
                RT->>DB: Guardar hash SHA-256, cadena y vencimiento de 8 h
                AS->>OB: Encolar identidad.login
                OB->>DB: INSERT event_outbox (PENDING)
                AS-->>AC: Access token y refresh token
                AC-->>C: 200 OK
            end
        end
    end
```

## Consideraciones técnicas

- El límite de bloqueo se evalúa por identidad: cinco fallos dentro de una ventana de quince minutos.
- La evaluación de anomalías es obligatoria y opera en modo *fail closed*: una indisponibilidad impide el acceso.
- Una decisión `BLOCK` queda en `anomaly_blocked_events`. Una aprobación administrativa vigente y coincidente puede consumirse una única vez en un intento posterior.
- El refresh token se almacena únicamente mediante su hash SHA-256; el valor en claro sólo se entrega al cliente.
- `identidad.login` se persiste en `event_outbox`; la entrega al adaptador configurado ocurre luego en el relay y no bloquea la respuesta de login.
