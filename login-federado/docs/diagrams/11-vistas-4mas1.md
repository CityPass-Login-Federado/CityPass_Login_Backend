# Arquitectura 4+1 — Módulo de Login Federado

**Estado documentado:** implementación actual (*as-is*)
**Fecha de revisión:** 24 de septiembre de 2026

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
        OPS[JWKS, Actuator y documentación]
    end

    subgraph Aplicacion[Aplicación]
        AS[Auth y Profile Services]
        PS[Panel Services]
        TS[Token Services]
        RS[Risk Service]
        ES[Event Publisher]
        MS[Mail Service]
    end

    subgraph Dominio[Dominio y políticas]
        CLIENTS[Registro de clientes y audiencias]
        POLICY[Autorización por groups y module]
        ROTATION[Rotación, cadenas y revocación]
        LOCKOUT[Bloqueo por intentos]
    end

    subgraph Infraestructura[Adaptadores e infraestructura]
        LDAP[Adaptador LDAP]
        DB[Repositorios PostgreSQL]
        JWT[JWT RS256 y JWKS]
        RISK[Cliente HTTP de anomalías]
        EDA[Logging o cliente HTTP EDA]
        SMTP[Correo SMTP]
    end

    AUTH --> AS
    OAUTH --> TS
    PANEL --> PS
    OPS --> JWT
    AS --> TS
    AS --> RS
    AS --> ES
    AS --> MS
    AS --> CLIENTS
    AS --> LOCKOUT
    PS --> POLICY
    PS --> TS
    TS --> ROTATION
    AS --> LDAP
    PS --> LDAP
    AS --> DB
    PS --> DB
    TS --> DB
    TS --> JWT
    RS --> RISK
    ES --> EDA
    MS --> SMTP
```

La identidad y los grupos viven en LDAP. PostgreSQL conserva tokens de renovación, intentos de acceso, solicitudes y tokens de restablecimiento, y auditoría del panel. La clave privada RSA queda restringida al emisor; la pública se expone mediante JWKS.

## 2. Vista de desarrollo

```mermaid
flowchart LR
    subgraph Repo[Repositorio login-federado]
        subgraph Java[Aplicación Spring Boot]
            CONTROLLERS[controller]
            SERVICES[service]
            SECURITY[security]
            LDAPJAVA[ldap]
            REPOSITORIES[repository]
            CONFIG[config]
            EVENTS[events]
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
    SERVICES --> LDAPJAVA
    SERVICES --> REPOSITORIES
    SERVICES --> EVENTS
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
    SERVICE --> EVENT{Publicador de eventos}
    EVENT -->|predeterminado| LOG[Registro local]
    EVENT -->|modo HTTP sincrónico| GATEWAY[Event Gateway]
    SERVICE --> MAIL[Envío de correo asíncrono]
```

- La API es esencialmente sin estado para access tokens; el estado de sesión renovable reside en PostgreSQL.
- LDAP, PostgreSQL y la evaluación de riesgo forman parte sincrónica de los flujos de autenticación.
- La rotación usa una actualización condicional para resolver concurrencia; el perdedor provoca la revocación de la cadena.
- El correo de recuperación se delega a un ejecutor asíncrono. La publicación EDA por HTTP, cuando se habilita, permanece sincrónica.
- No hay transacción distribuida entre LDAP, PostgreSQL, SMTP y el gateway; se documentan explícitamente los puntos de consistencia eventual o mejor esfuerzo.

## 4. Vista física

La topología local se detalla en [12-cloud-deployment-dev.md](12-cloud-deployment-dev.md) y la topología de producción en [13-cloud-deployment-prod.md](13-cloud-deployment-prod.md). En resumen, desarrollo ejecuta los componentes mediante Docker Compose; producción separa el backend y LDAP del servicio de anomalías, y utiliza PostgreSQL administrado.

## +1. Escenarios representativos

| Escenario | Recorrido arquitectónico | Resultado esperado |
| --- | --- | --- |
| Login humano | REST → registro de clientes → bloqueo → LDAP → riesgo → JWT/refresh → evento | Access token de 15 min y refresh token de 8 h |
| Renovación | REST → hash en PostgreSQL → rotación atómica → LDAP → JWT → evento | Nuevos tokens; la reutilización revoca la cadena |
| Logout | REST público → revocación de un refresh token → evento | `204` idempotente; el access token expira naturalmente |
| Recuperación de contraseña | REST → límites en PostgreSQL → correo → cambio en LDAP → revocación masiva | Respuesta no enumerativa y cierre de sesiones renovables |
| Administración delegada | JWT `aud=admin` → política groups/module → LDAP → auditoría | Operación limitada al módulo del delegado |
| Administración global | JWT `aud=admin` → política admin-global → módulo explícito o ruta global | Operación transversal controlada |
| Integración M2M | Formulario OAuth → registro de clientes → JWT de servicio → JWKS | Token sin groups/module/refresh, válido por 15 min |
| Publicación EDA | Evento crudo → logging o gateway HTTP autenticado | Evento disponible para consumidores; sin agregación local |

## Trazabilidad

- Contexto C4: `01-c4-contexto.md`.
- Contenedores C4: `02-c4-contenedores.md`.
- Componentes C4: `03-c4-componentes.md`.
- Secuencias: `04` a `10`.
- Despliegues: `12` y `13`.
