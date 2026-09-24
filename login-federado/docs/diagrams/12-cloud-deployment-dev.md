# Cloud / Deployment — Entorno de desarrollo

**Fecha de revisión:** 24 de septiembre de 2026
**Artefacto fuente:** Docker Compose de desarrollo

```mermaid
flowchart TB
    DEV[Desarrollador o cliente local]

    subgraph HOST[Host Docker de desarrollo]
        APP[login-federado-app<br/>Spring Boot<br/>puerto publicado 8080]
        LDAP[openldap<br/>LDAP 389]
        INIT[ldap-config<br/>configuración de una ejecución]
        PG[postgres<br/>PostgreSQL]
        ANOM[anomaly-detection<br/>FastAPI /score<br/>puerto publicado 8000]
        PGDATA[(Volumen pgdata)]
        KEYS[Claves RSA dentro del contenedor<br/>sin volumen persistente dedicado]
        CFG[Archivos LDIF y configuración]
    end

    SMTP[Servidor SMTP configurado<br/>Mailpit opcional y comentado]
    EDA[Event Gateway opcional]

    DEV -->|HTTP :8080| APP
    DEV -->|HTTP :8000, diagnóstico| ANOM
    APP -->|LDAP| LDAP
    INIT -->|Carga inicial LDIF| LDAP
    CFG --> INIT
    APP -->|JDBC| PG
    ANOM -->|Consulta de intentos| PG
    PG --> PGDATA
    APP --> KEYS
    APP -->|HTTP /score| ANOM
    APP -. SMTP si se configura .-> SMTP
    APP -. HTTP si se habilita EDA .-> EDA
```

## Inventario

| Unidad | Responsabilidad | Persistencia / estado |
| --- | --- | --- |
| Aplicación Spring Boot | REST, OAuth, panel, JWT, LDAP, eventos y correo | Estado durable en LDAP/PostgreSQL; claves locales al contenedor |
| OpenLDAP | Identidades, atributos, grupos y membresías | Datos del contenedor; inicialización por `ldap-config` |
| `ldap-config` | Aplica configuración y datos LDIF | Proceso de una sola ejecución |
| PostgreSQL | Refresh tokens, intentos, auditoría y recuperación | Volumen Docker `pgdata` |
| FastAPI | Puntaje de anomalía | Consulta PostgreSQL; usa modelo sólo si existe en la ruta runtime |

## Observaciones

- El esquema SQL usa creación idempotente de tablas y no recrea destructivamente la base en cada inicio.
- El runtime de anomalías puede operar con reglas determinísticas. El repositorio no contiene actualmente `anomaly-detection/models/isolation_forest.pkl` como artefacto desplegable.
- El publicador predeterminado es local por logging; el gateway EDA no es necesario para levantar el módulo aislado.
- El correo requiere SMTP. Mailpit aparece como alternativa local opcional, no como dependencia activa del Compose.
- La falta de un volumen dedicado para claves en desarrollo implica que una recreación puede cambiar el material criptográfico según la configuración utilizada.
