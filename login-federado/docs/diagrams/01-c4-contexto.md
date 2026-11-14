# Arquitectura y despliegue

Estas vistas representan el estado comprobado en los repositorios backend y frontend al 14 de noviembre de 2026. Las líneas punteadas indican una integración prevista o incompleta, no un flujo productivo.

## C4 — contexto

```mermaid
flowchart LR
    U[Usuario final]
    A[Delegado de módulo]
    FE[CityPass_Login_Frontend\nReact 18 + TypeScript]
    IDP[CityPass_Login_Backend\nSpring Boot 3.3.13]
    LDAP[(OpenLDAP\nidentidades y grupos)]
    DB[(PostgreSQL 16\ntokens, intentos, auditoría)]
    ML[anomaly-detection\nFastAPI]
    SQ[APIs de otros squads]
    BUS[Event Bus Squad 1\nno integrado]

    U -->|HTTPS / login| FE
    A -->|HTTPS / panel| FE
    FE -->|REST JSON| IDP
    IDP -->|búsqueda y bind LDAP| LDAP
    IDP -->|JPA / JDBC| DB
    IDP -->|POST /score| ML
    ML -->|SQL de solo lectura pretendido| DB
    IDP -->|JWKS + JWT RS256| SQ
    IDP -.->|usuario.autenticado\nhoy solo log| BUS
```

Los consumidores validan localmente JWT mediante `/.well-known/jwks.json`. No hay introspección ni sesión HTTP compartida.

## C4 — contenedores y componentes principales

```mermaid
flowchart TB
    subgraph Browser[Browser]
        UI[Pages y componentes]
        AUTHF[Feature auth]
        PANELF[Feature systemManagement]
        HTTP[Axios + refresh coordinado]
        MEM[(tokenVault en memoria)]
        UI --> AUTHF
        UI --> PANELF
        AUTHF --> HTTP
        PANELF --> HTTP
        AUTHF --> MEM
    end

    subgraph Java[Contenedor login-federado :8081]
        CTRL[Controllers REST]
        AUTH[AuthService]
        DIR[LdapDirectory]
        PNL[PanelDirectoryService]
        TOK[AccessTokenIssuer y RefreshTokenService]
        RISK[AnomalyRiskClient]
        AUD[PanelAuditService]
        EVT[EventPublisher]
        SEC[Spring Security Resource Server]
        CTRL --> AUTH
        CTRL --> PNL
        AUTH --> DIR
        AUTH --> TOK
        AUTH --> RISK
        AUTH --> EVT
        PNL --> AUD
        SEC --> CTRL
    end

    subgraph Infra[Infraestructura local]
        LDAP[(OpenLDAP :389/:636)]
        PG[(PostgreSQL :5432)]
        PY[FastAPI :8000]
        LOG[(Logs de aplicación)]
    end

    HTTP -->|REST; contrato de panel hoy divergente| CTRL
    DIR --> LDAP
    PNL --> LDAP
    TOK --> PG
    AUD --> PG
    AUTH --> PG
    RISK --> PY
    PY --> PG
    EVT --> LOG
```

### Responsabilidades

| Contenedor | Responsabilidad | Persistencia |
|---|---|---|
| Frontend | navegación, formularios, caché, token en memoria y renovación | ninguna persistente |
| Spring Boot | autenticación, emisión/validación JWT, refresh, lockout y panel | PostgreSQL + LDAP |
| OpenLDAP | fuente de verdad de personas, contraseñas, estado y grupos | DIT LDAP |
| PostgreSQL | refresh tokens, intentos y auditoría del panel | tres tablas de `schema.sql` |
| FastAPI | features históricas y decisión de riesgo | lee `login_attempts` |

## Despliegue local real

```mermaid
flowchart LR
    subgraph Host[Host Docker]
        subgraph Net[Red de Docker Compose del backend]
            APP[login-federado\nJava 21 JRE\n:8081]
            PY[anomaly-detection\nPython 3.12\n:8000]
            LDAP[openldap\n1.5.0\n:389/:636]
            INIT[ldap-config\none-shot]
            PG[postgres\n16-alpine\n:5432]
            VOL[(pgdata)]
            INIT --> LDAP
            APP --> LDAP
            APP --> PG
            APP --> PY
            PY --> PG
            PG --> VOL
        end
        NGINX[Frontend Nginx 1.27\n:8080 interno / :5173 host]
        NGINX -->|host.docker.internal:8081| APP
    end
    B[Browser] -->|localhost:5173| NGINX
```

No se configura TLS, balanceador, réplica, observabilidad central ni orquestador cloud en el backend.

## CI/CD y cloud comprobados

```mermaid
flowchart LR
    PR[Pull request]
    PUSH[Push main/develop]
    BCI[Backend pr-checks.yml]
    FCI[Frontend CI.yml]
    SMOKE[Frontend smoke-tests.yml]
    CD[Frontend CD.yml]
    GHCR[(GitHub Container Registry)]
    ART[(GitHub artifact\nVercel prebuilt)]
    MAN[manual-deploy.yml]
    VERCEL[Vercel production]

    PR --> BCI
    PR --> FCI
    PR --> SMOKE
    PUSH --> FCI
    PUSH --> CD
    BCI -->|Maven verify, Surefire, Gitleaks, Trivy| BCI
    FCI -->|lint, coverage, build, Sonar, Gitleaks, Trivy| FCI
    CD --> GHCR
    CD --> ART
    ART --> MAN
    MAN --> VERCEL
```

El frontend sí define publicación y despliegue Vercel. El backend solo tiene validación de PR/push en un único workflow y no publica ni despliega una imagen. `smoke-tests.yml` pertenece al frontend y verifica únicamente HTML servido por Nginx.

## Vista 4+1 resumida

- Lógica: capas controller/service/repository/config, más adaptadores LDAP, riesgo y eventos.
- Desarrollo: monorepo backend con dos servicios; frontend Feature-Driven en repositorio separado.
- Procesos: login síncrono; publicación de evento síncrona al logger; refresh transaccional en SQL.
- Física: cinco servicios Compose del backend y un contenedor frontend separado.
- Escenarios: login, refresh/logout, client credentials y CRUD LDAP administrativo; vea [interacciones](04-sequence-login.md).
