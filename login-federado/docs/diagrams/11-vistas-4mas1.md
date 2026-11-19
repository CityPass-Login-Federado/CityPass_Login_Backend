# Arquitectura 4+1 — Módulo de Login Federado

**Estado documentado:** implementación actual (*as-is*)
**Fecha de revisión:** 19 de noviembre de 2026

El modelo 4+1 organiza la arquitectura desde cinco perspectivas complementarias. El sistema de interés es exclusivamente el Módulo de Login Federado e Identidad; los demás módulos CityPass+ se consideran consumidores externos durante la etapa de desarrollo aislado.

```mermaid
flowchart TB
    S[Escenarios +1<br/>Casos funcionales verificables]
    L[Vista lógica<br/>Responsabilidades y datos]
    D[Vista de desarrollo<br/>Organización del código]
    P[Vista de procesos<br/>Ejecución y concurrencia]
    F[Vista física<br/>Despliegue e infraestructura]
    S --- L
    S --- D
    S --- P
    S --- F
```

## 1. Vista lógica

```mermaid
flowchart TB
    subgraph Presentacion[Interfaces]
        AUTH[REST de autenticación y perfil]
        OAUTH[OAuth 2.0 Client Credentials]
        PANEL[REST del panel administrativo]
        ANALYTICS[Proxy REST de Analítica]
        OPS[JWKS, Actuator y documentación]
    end

    subgraph Aplicacion[Aplicación]
        AS[Auth y Profile Services]
        PS[Panel Services]
        TS[Token Services]
        RS[Risk Service]
        OUTBOX[Outbox Service y Relay]
        ES[Event Publisher]
        AP[Analytics Proxy]
        MS[Mail Service]
    end

    subgraph Dominio[Dominio y políticas]
        CLIENTS[Registro de clientes y audiencias]
        POLICY[Autorización por groups y module]
        ROTATION[Rotación y cadenas de refresh]
        REVOCATION[Denylist por jti y corte por sujeto]
        LOCKOUT[Bloqueo por intentos]
    end

    subgraph Infraestructura[Adaptadores e infraestructura]
        LDAP[Adaptador LDAP]
        DB[Repositorios PostgreSQL]
        JWT[JWT RS256 y JWKS]
        RISK[Cliente HTTP de anomalías]
        EDA[Logging o cliente HTTP EDA]
        ANALYTICS_EXT[Cliente HTTP de Analítica]
        SMTP[Correo SMTP]
    end

    AUTH --> AS
    OAUTH --> TS
    PANEL --> PS
    ANALYTICS --> AP
    OPS --> JWT
    AS --> TS
    AS --> RS
    AS --> OUTBOX
    AS --> MS
    AS --> CLIENTS
    AS --> LOCKOUT
    PS --> POLICY
    PS --> TS
    TS --> ROTATION
    TS --> REVOCATION
    AS --> LDAP
    PS --> LDAP
    AS --> DB
    PS --> DB
    TS --> DB
    TS --> JWT
    RS --> RISK
    OUTBOX --> DB
    OUTBOX --> ES
    ES --> EDA
    AP --> POLICY
    AP --> ANALYTICS_EXT
    MS --> SMTP
```

La identidad y los grupos viven en LDAP. PostgreSQL conserva las diez tablas operativas de sesiones, seguridad, anomalías, auditoría, recuperación y Outbox. La clave privada RSA queda restringida al emisor; la pública se expone mediante JWKS. Analítica y el Event Gateway son sistemas externos accedidos a través de adaptadores explícitos.

## 2. Vista de desarrollo

```mermaid
flowchart LR
    subgraph Repo[Repositorio login-federado]
        subgraph Java[Aplicación Spring Boot]
            CONTROLLERS[controller]
            SERVICES[service]
            SECURITY[security]
            IDENTITY[identity]
            REPOSITORIES[repository]
            CONFIG[config]
            EVENTS[event]
            ANALYTICSJAVA[analytics]
            PANELJAVA[panel]
        end
        subgraph Python[Servicio FastAPI]
            RUNTIME[anomaly-detection<br/>runtime /score]
            EXP[anomaly-detection/ML<br/>experimentación y entrenamiento]
            MODEL[models/isolation_forest.pkl<br/>artefacto runtime opcional]
        end
        DEPLOY[Docker, Compose y workflows]
        DOCS[docs/diagrams]
    end

    CONTROLLERS --> SERVICES
    SERVICES --> SECURITY
    SERVICES --> IDENTITY
    SERVICES --> REPOSITORIES
    SERVICES --> EVENTS
    CONTROLLERS --> PANELJAVA
    CONTROLLERS --> ANALYTICSJAVA
    ANALYTICSJAVA --> SECURITY
    CONFIG --> CONTROLLERS
    CONFIG --> SERVICES
    RUNTIME -. carga si existe .-> MODEL
    EXP -. genera un modelo experimental;<br/>no se copia automáticamente .-> MODEL
    DEPLOY --> Java
    DEPLOY --> RUNTIME
```

La aplicación Java concentra el contrato de identidad. FastAPI es un proceso separado y su modelo de aislamiento es opcional: si el archivo no está en la ruta runtime, aplica reglas determinísticas. El artefacto entrenado bajo `ML/models` no se empaqueta automáticamente como `models/isolation_forest.pkl`.

## 3. Vista de procesos

```mermaid
flowchart TB
    REQ[Solicitud HTTP] --> FILTER[Filtros de seguridad]
    FILTER --> CTRL[Controller]
    CTRL --> SERVICE[Servicio de aplicación]
    SERVICE --> LDAP[(LDAP)]
    SERVICE --> PG[(PostgreSQL)]
    SERVICE --> RISK[FastAPI /score]
    SERVICE --> TOKEN[Firma JWT local]
    SERVICE --> OUTBOX[(event_outbox)]
    SERVICE --> RESPONSE[Respuesta HTTP desacoplada del gateway]
    SCHEDULER[EventOutboxRelay programado] -->|SKIP LOCKED| OUTBOX
    SCHEDULER --> EVENT{Publicador configurado}
    EVENT -->|predeterminado| LOG[Registro local]
    EVENT -->|modo HTTP| GATEWAY[Event Gateway - Grupo 1]
    EVENT -->|éxito| SENT[Marcar SENT]
    EVENT -->|error| RETRY[Reprogramar con backoff<br/>o marcar FAILED]
    ANALYTICS_REQ[GET /admin/analitica] --> AUTHZ[Exigir admin-global]
    AUTHZ --> ANALYTICS_EXT[Backend de Analítica - Grupo 8]
    ANALYTICS_EXT --> ANALYTICS_REQ
    SERVICE --> MAIL[Envío de correo asíncrono]
```

- Los access tokens son autosuficientes, pero su revocación inmediata agrega estado acotado en `revoked_access_tokens` y `revoked_subject_cutoffs`.
- LDAP, PostgreSQL y la evaluación de riesgo forman parte sincrónica de los flujos de autenticación.
- La rotación usa una actualización condicional para resolver concurrencia; el perdedor provoca la revocación de la cadena.
- El correo de recuperación se delega a un ejecutor asíncrono. Los requests de autenticación encolan eventos; el relay los entrega después con lotes, `SKIP LOCKED`, `Retry-After` y *backoff* exponencial.
- El proxy de Analítica reenvía el token humano y valida que una respuesta exitosa sea HTML no vacío; mapea fallas de comunicación a `503` y respuestas exitosas inválidas a `502`.
- No hay transacción distribuida entre LDAP, PostgreSQL, SMTP y el gateway; se documentan explícitamente los puntos de consistencia eventual o mejor esfuerzo.

## 4. Vista física

La topología local se detalla en [12-cloud-deployment-dev.md](12-cloud-deployment-dev.md) y la topología de producción en [13-cloud-deployment-prod.md](13-cloud-deployment-prod.md). En resumen, desarrollo ejecuta los componentes mediante Docker Compose; producción separa el backend y LDAP del servicio de anomalías, y utiliza PostgreSQL administrado.

## +1. Escenarios representativos

| Escenario | Recorrido arquitectónico | Resultado esperado |
| --- | --- | --- |
| Login humano | REST → rate limit/bloqueo → LDAP → riesgo → JWT/refresh → Outbox | Access token de 15 min, refresh de 8 h y evento pendiente |
| Renovación | REST → rotación atómica → LDAP → JWT → Outbox | Nuevos tokens; la reutilización revoca la cadena |
| Logout | REST público → revocación de refresh → denylist opcional del access → Outbox | `204` idempotente; este backend rechaza el access presentado |
| Recuperación de contraseña | REST → límites → correo → cambio en LDAP → revocación por sujeto | Respuesta no enumerativa; refresh cerrados y access previos rechazados por este backend |
| Administración delegada | JWT `aud=citypass-admin-api` → política groups/module → LDAP → auditoría | Operación limitada al módulo del delegado |
| Administración global | JWT `aud=citypass-admin-api` → admin-global → módulo explícito o ruta global | Operación transversal controlada |
| Integración M2M | Formulario OAuth → registro de clientes → JWT de servicio → JWKS | Token sin groups/module/refresh, válido por 15 min |
| Publicación EDA | Evento crudo → `event_outbox` → relay → logging o Gateway autenticado | Entrega reintentable; filas `SENT` o `FAILED` trazables |
| Consulta de Analítica | Frontend → proxy con JWT → backend Grupo 8 → HTML | Tablero visible solo para `admin-global` |

## Trazabilidad

- Contexto C4: `01-c4-contexto.md`.
- Contenedores C4: `02-c4-contenedores.md`.
- Componentes C4: `03-c4-componentes.md`.
- Secuencias: `04` a `10`.
- Despliegues: `12` y `13`.
