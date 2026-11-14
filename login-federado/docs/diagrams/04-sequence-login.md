# Diagramas de interacción

## Flujo 1 — login, riesgo, tokens y evento

```mermaid
sequenceDiagram
    actor U as Usuario
    participant F as Frontend
    participant C as AuthController
    participant A as AuthService
    participant L as LoginAttemptService
    participant D as OpenLDAP
    participant R as anomaly-detection
    participant P as PostgreSQL
    participant T as Token services
    participant E as LoggingEventPublisher

    U->>F: usuario + contraseña
    F->>C: POST /auth/login {username,password,clientId}
    C->>A: login(request, IP, User-Agent)
    A->>L: assertNotLocked(username)
    L->>P: contar fallos en últimos 15 min
    A->>D: buscar uid global
    A->>D: bind con DN y contraseña
    D-->>A: autenticado
    A->>R: POST /score
    R->>P: leer hasta 200 login_attempts
    R-->>A: ALLOW, REVIEW o BLOCK
    Note over R: Sin .pkl versionado usa reglas;<br/>REVIEW no se persiste como flag.
    A->>L: recordAttempt(success=true)
    L->>P: INSERT login_attempts
    A->>T: issueHuman + issueInitial
    T->>P: INSERT refresh_tokens (hash)
    T-->>A: JWT RS256 + refresh opaco
    A->>E: publish(usuario.autenticado)
    E-->>E: serializar JSON y escribir log
    A-->>F: 200 access_token + refresh_token
    F-->>U: sesión en memoria
```

El riesgo se consulta después de un bind LDAP exitoso, no antes. No existe la entidad `RawAuthenticationEvent`: el histórico real es `login_attempts`.

## Flujo 2 — fuerza bruta, anomalía o indisponibilidad

```mermaid
sequenceDiagram
    actor U as Usuario
    participant A as AuthService
    participant L as LoginAttemptService
    participant D as OpenLDAP
    participant R as AnomalyRiskClient
    participant H as GlobalExceptionHandler
    participant P as PostgreSQL

    U->>A: login
    A->>L: assertNotLocked
    alt 5 o más fallos en 15 minutos
        L-->>A: AccountLockedException
        A-->>H: se convierte en BadCredentialsException
        H-->>U: HTTP 401 genérico
    else credencial o módulo inválido
        A->>D: búsqueda/bind
        A->>L: recordAttempt(false)
        L->>P: INSERT login_attempts
        A-->>H: BadCredentialsException
        H-->>U: HTTP 401 genérico
    else LDAP válido
        A->>R: score
        alt decision BLOCK
            R-->>A: BLOCK + reasons
            A->>L: recordAttempt(false)
            L->>P: INSERT login_attempts
            A-->>H: BadCredentialsException
            H-->>U: HTTP 401 genérico
        else servicio de riesgo no disponible
            R-->>A: AnomalyServiceUnavailableException
            H-->>U: HTTP 401 genérico
            Note over A,P: Este camino no registra login_attempts.
        else REVIEW o ALLOW
            R-->>A: continuar
        end
    end
```

Aunque las clases `AnomalyBlockedException` y `AnomalyServiceUnavailableException` tienen handlers, `AuthService` convierte `BLOCK` directamente en `BadCredentialsException`. Todos estos caminos responden 401, no 403/503, para evitar enumeración; por tanto, una defensa que afirme 403/503 no coincide con el código.

## Flujo 3 — operación administrativa

```mermaid
sequenceDiagram
    actor D as Delegado
    participant F as Frontend
    participant S as Spring Security
    participant C as PanelController
    participant Z as PanelAuthorization
    participant P as PanelDirectoryService
    participant L as OpenLDAP
    participant A as PanelAuditService
    participant DB as PostgreSQL

    D->>F: alta/edición/membresía
    F->>S: request con Bearer JWT
    S->>S: validar firma y expiración
    S->>C: Jwt autenticado
    C->>Z: requireDelegate(jwt)
    Z->>Z: aud + token_use + ver + delegados + module
    Z-->>C: Delegate(sub, uid, module)
    C->>P: operación dentro del module del token
    P->>L: bind/modify/rename/unbind
    L-->>P: resultado
    P->>A: record(actor, action, target, detail)
    A->>DB: INSERT panel_audit
    A-->>P: éxito o error absorbido y logueado
    P-->>F: DTO o 204
```

No se publica un evento de dominio administrativo. La auditoría SQL es el único efecto secundario, y `PanelAuditService` absorbe fallos de persistencia; por ello no garantiza atomicidad LDAP/SQL ni auditoría inmutable a nivel de base.

## Flujo de refresh y detección de reuso

```mermaid
sequenceDiagram
    participant F as Frontend
    participant R as RefreshTokenService
    participant P as PostgreSQL
    participant L as OpenLDAP
    participant T as AccessTokenIssuer

    F->>R: POST /auth/refresh {refreshToken}
    R->>P: buscar SHA-256(token)
    alt token ya revocado
        R->>P: revocar toda chain_id
        R-->>F: 401 genérico
    else expirado o desconocido
        R-->>F: 401 genérico
    else usable
        R->>P: marcar token actual revoked_at
        R->>L: reloadBySub y grupos actuales
        L-->>R: persona vigente
        R->>T: emitir nuevo access token
        R->>P: insertar siguiente refresh en misma chain_id
        R-->>F: nuevo par de tokens
    end
```
