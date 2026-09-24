# Secuencia — Inicio de sesión federado

**Fecha de revisión:** 24 de septiembre de 2026
**Endpoint:** `POST /auth/login`

El inicio de sesión humano exige `username`, `password` y `clientId`. La respuesta de error no revela si falló el usuario, la contraseña, la pertenencia al módulo o la evaluación de riesgo.

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant C as Cliente del módulo
    participant AC as AuthController
    participant AS as AuthService
    participant CR as ClientRegistry
    participant LA as LoginAttemptService
    participant LDAP as LDAP corporativo
    participant RA as Servicio de anomalías
    participant JWT as AccessTokenIssuer
    participant RT as RefreshTokenService
    participant DB as PostgreSQL
    participant EP as EventPublisher

    U->>C: Ingresa credenciales
    C->>AC: POST /auth/login<br/>{username, password, clientId}
    AC->>AS: authenticate(request)
    AS->>CR: requireHumanClient(clientId)
    CR-->>AS: Cliente, audiencia y módulo
    AS->>LA: assertNotLocked(username)
    LA->>DB: Consultar intentos recientes
    alt Cinco fallos en 15 minutos
        LA-->>AS: Cuenta temporalmente bloqueada
        AS-->>AC: Error de autenticación
        AC-->>C: 423 Locked
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
            alt Servicio no disponible o decisión BLOCK
                AS->>LA: recordFailure(username)
                LA->>DB: Registrar intento fallido
                AS-->>AC: Error genérico de autenticación
                AC-->>C: 401 Unauthorized
            else Decisión ALLOW o REVIEW
                Note over AS,RA: REVIEW permite continuar,<br/>la decisión no se persiste actualmente
                AS->>LA: recordSuccess(username)
                LA->>DB: Limpiar estado de bloqueo
                AS->>JWT: Emitir access token humano RS256, 15 min
                Note over JWT: sub=employeeNumber, aud=cliente,<br/>token_use=access, ver, preferred_username,<br/>module y groups
                AS->>RT: createInitialToken(subject, client)
                RT->>DB: Guardar hash SHA-256, cadena y vencimiento de 8 h
                AS->>EP: Publicar identidad.login
                AS-->>AC: Access token y refresh token
                AC-->>C: 200 OK
            end
        end
    end
```

## Consideraciones técnicas

- El límite de bloqueo se evalúa por identidad: cinco fallos dentro de una ventana de quince minutos.
- La evaluación de anomalías es obligatoria y opera en modo *fail closed*: una indisponibilidad impide el acceso.
- El refresh token se almacena únicamente mediante su hash SHA-256; el valor en claro sólo se entrega al cliente.
- La publicación de `identidad.login` usa el publicador configurado. En el modo HTTP es sincrónica, por lo que un fallo del gateway puede ocurrir después de haber persistido el estado de autenticación.
