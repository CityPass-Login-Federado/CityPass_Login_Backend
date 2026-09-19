# Despliegue — Desarrollo local con Docker Compose

**Ambiente:** desarrollo.

**Fuente de verdad:** `docker-compose.yml`, `Dockerfile` y `application.yml`.

```mermaid
flowchart LR
    DEV["Desarrollador / cliente local"]

    subgraph HOST["Nodo de despliegue · Equipo de desarrollo"]
        subgraph NET["Docker Engine · red de Docker Compose"]
            APP["Instancia de contenedor<br/><b>citypass-login</b><br/>Java 21 · Spring Boot<br/>puerto 8081"]
            LDAP["Instancia de contenedor<br/><b>citypass-ldap</b><br/>OpenLDAP<br/>puertos 389/636"]
            CFG["Contenedor one-shot<br/><b>citypass-ldap-config</b><br/>overlays, ACLs y seed"]
            PG[("Instancia de contenedor<br/><b>citypass-db</b><br/>PostgreSQL 16<br/>puerto 5432")]
            ANOM["Instancia de contenedor<br/><b>citypass-anomaly-detection</b><br/>Python 3.12 · FastAPI<br/>puerto 8000"]
            KEYS["Filesystem del contenedor<br/>/app/keys<br/>claves RSA autogeneradas"]
        end

        PGVOL[("Volumen nombrado<br/>pgdata")]
        LDAPCFG["Bind mount read-only<br/>./ldap/config"]
    end

    DEV -->|"HTTP localhost:8081"| APP
    DEV -->|"HTTP localhost:8000"| ANOM
    DEV -->|"LDAP localhost:389/636"| LDAP
    DEV -->|"SQL localhost:5432"| PG

    APP -->|"LDAP search/bind/modify"| LDAP
    APP -->|"JDBC"| PG
    APP -->|"HTTP/JSON POST /score"| ANOM
    ANOM -->|"SQL para features"| PG

    CFG -.->|espera healthcheck| LDAP
    LDAPCFG --> CFG
    APP -.->|espera LDAP sano, configuración completa,<br/>PostgreSQL y anomalías iniciados| CFG
    PG --- PGVOL
    APP --- KEYS
```

## Inventario y puertos

| Instancia | Imagen/build | Puerto publicado | Persistencia/configuración |
|---|---|---|---|
| `citypass-login` | Build local de `login-federado/Dockerfile` | `8081:8081` | Claves RSA en `/app/keys` dentro del contenedor |
| `citypass-ldap` | `osixia/openldap:1.5.0` | `389:389`, `636:636` | Configurado por el job `ldap-config` |
| `citypass-ldap-config` | `osixia/openldap:1.5.0` | No aplica | `./ldap/config:/config:ro`; termina al aplicar configuración y seed |
| `citypass-db` | `postgres:16-alpine` | `5432:5432` | Volumen nombrado `pgdata` |
| `citypass-anomaly-detection` | Build local de `anomaly-detection/Dockerfile` | `8000:8000` | Consulta la misma PostgreSQL de desarrollo |

## Consideraciones

- Este ambiente está pensado para desarrollo y pruebas; los secretos incluidos en Compose son valores locales.
- `schema.sql` se ejecuta en cada arranque y recrea las tablas de autenticación, por lo que los refresh tokens dejan de ser válidos.
- El Event Bus no forma parte de este despliegue: los eventos se escriben en logs.
