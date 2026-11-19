# Cloud / Deployment — Entorno de producción

**Fecha de revisión:** 19 de noviembre de 2026
**Artefactos fuente:** Docker Compose de producción y workflows CI/CD

```mermaid
flowchart TB
    USER[Usuarios y administradores]
    MODULES[Módulos consumidores CityPass+]
    REPO[Repositorio GitHub]
    ACTIONS[GitHub Actions<br/>CI y CD]
    GHCR[GitHub Container Registry]
    SECRETS[GitHub Secrets]

    subgraph OCI[Oracle Cloud Infrastructure]
        subgraph VM1[VM 1 - Backend]
            APP[Spring Boot<br/>API + EventOutboxRelay]
            LDAP[OpenLDAP]
            INIT[Configuración LDAP<br/>una ejecución]
            KEYS[(Volumen keysdata<br/>claves RSA)]
        end
        subgraph VM2[VM 2 - Anomalías]
            ANOM[FastAPI /score]
        end
    end

    SUPA[(Supabase PostgreSQL<br/>pooler con TLS)]
    SMTP[Proveedor SMTP]
    EDA[Event Gateway - Grupo 1]
    ANALYTICS[Backend de Analítica - Grupo 8]

    USER -->|HTTPS según endpoint publicado| APP
    MODULES -->|REST / OAuth / JWKS| APP
    APP -->|LDAP| LDAP
    INIT -->|LDIF inicial| LDAP
    APP --> KEYS
    APP -->|JDBC TLS| SUPA
    ANOM -->|JDBC TLS| SUPA
    APP -->|HTTP entre VMs| ANOM
    APP -->|SMTP| SMTP
    APP -.->|OAuth 2.0 + HTTPS<br/>si EDA_PUBLISHER=http| EDA
    APP -->|HTTPS + Bearer humano<br/>proxy de HTML| ANALYTICS

    REPO --> ACTIONS
    SECRETS --> ACTIONS
    ACTIONS -->|Build y push| GHCR
    GHCR -->|Pull de imágenes| VM1
    GHCR -->|Pull de imagen| VM2
    ACTIONS -->|SSH y Docker Compose| VM1
    ACTIONS -->|SSH y Docker Compose| VM2
```

## Flujo CI/CD vigente

1. La integración continua ejecuta compilación, lint, pruebas y análisis Sonar, además de Gitleaks y análisis Trivy de configuración e imágenes.
2. La entrega continua construye y publica imágenes en GHCR.
3. El despliegue conecta por SSH a las dos VMs y actualiza los servicios con sus Compose correspondientes.
4. El backend verifica salud mediante Actuator. Tanto `/actuator/health` como el endpoint simple `/health` están permitidos públicamente por `SecurityConfig`.

## Persistencia y secretos

| Recurso | Tratamiento actual |
| --- | --- |
| PostgreSQL | Servicio administrado Supabase mediante pooler y TLS |
| Outbox EDA | Tabla `event_outbox` en Supabase; relay, reintentos y backoff dentro del contenedor Spring Boot |
| Claves RSA | Volumen `keysdata` montado en `/app/keys` en VM 1 |
| Credenciales y hosts | Inyectados desde secretos/variables de despliegue |
| LDAP | Ejecutado en VM 1; el Compose productivo no declara volumen de datos LDAP |
| Modelo de anomalía | Incluido sólo si el artefacto runtime se incorpora a la imagen de FastAPI |

## Riesgos y acciones pendientes de infraestructura

- **Durabilidad LDAP:** declarar y respaldar un volumen de datos para evitar pérdida de identidades al recrear el contenedor.
- **Perímetro y TLS:** documentar reverse proxy, certificados, DNS, puertos publicados y reglas de red; no están definidos por los Compose revisados.
- **Comunicación entre VMs:** restringir el acceso a FastAPI y cifrar o aislar el tráfico según la red disponible.
- **Claves de firma:** definir respaldo, rotación y conservación del volumen `keysdata`, manteniendo la clave privada fuera de imágenes y repositorio.
- **EDA:** validar en cada despliegue `EDA_PUBLISHER=http`, las URLs y la pareja `EDA_CLIENT_ID=grupo2` / secreto correspondiente; monitorear filas `PENDING` y `FAILED` del Outbox.
- **Analítica:** el backend externo se configura con `ANALYTICS_BASE_URL` y `ANALYTICS_EMBED_PATH`; conservar timeouts y conectividad saliente desde VM 1.
- **Infraestructura como código:** el repositorio automatiza aplicaciones y contenedores, pero no aprovisiona VMs, red, DNS, certificados ni Supabase. Ese alcance debe documentarse o incorporarse antes de afirmar IaC completa.
- **Retención de eventos:** definir una política de archivo o purga para filas `SENT` y tratamiento operativo de filas `FAILED`; el código actual no las elimina automáticamente.
