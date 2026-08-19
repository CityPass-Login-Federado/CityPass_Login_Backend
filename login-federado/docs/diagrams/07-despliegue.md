# Deployment Diagram — Login Federado

## Desarrollo (Docker Compose local)

```mermaid
graph TB
    subgraph "Desarrollador"
        DEV[Dev Machine<br/>Java 21 + Maven]
    end

    subgraph "Docker Compose"
        subgraph "citypass-ldap"
            LDAP[OpenLDAP 1.5.0<br/>Puerto 389/636]
            SEED[LDAP Seed Data<br/>ou=usuarios, ou=grupos]
        end
        subgraph "citypass-db"
            PG[PostgreSQL 16<br/>Puerto 5432]
            VOL[(pgdata volume)]
        end
    end

    subgraph "Spring Boot (fuera de Docker)"
        APP[login-federado<br/>Puerto 8081]
        KEYS[(keys/<br/>RSA keypair)]
    end

    DEV -->|mvn spring-boot:run| APP
    APP -->|ldap://localhost:389| LDAP
    APP -->|jdbc:postgresql://localhost:5432| PG
    LDAP --> SEED
    PG --> VOL
```

## Producción (Cloud)

```mermaid
graph TB
    subgraph "Cloud Provider (AWS/GCP/Azure)"
        subgraph "Container Orchestrator"
            LB[Load Balancer]
            APP1[login-federado inst. 1]
            APP2[login-federado inst. 2]
        end

        subgraph "Managed Services"
            LDAP_C[LDAP Managed<br/>o containerizado]
            DB_C[PostgreSQL Managed<br/>RDS/Cloud SQL/Azure SQL]
            CACHE_C[ElastiCache/Redis<br/>opcional]
        end

        subgraph "Secrets"
            SM[Secret Manager<br/>LDAP passwords, DB credentials]
        end
    end

    CLIENT[Client App] -->|HTTPS| LB
    LB --> APP1
    LB --> APP2
    APP1 -->|LDAP bind| LDAP_C
    APP1 -->|JDBC| DB_C
    APP1 -->|Lee secrets| SM
    APP2 -->|LDAP bind| LDAP_C
    APP2 -->|JDBC| DB_C
    APP2 -->|Lee secrets| SM
```

## Endpoints expuestos

| Endpoint | Método | Auth | Descripción |
|----------|--------|------|-------------|
| `/auth/login` | POST | Público | Login contra LDAP |
| `/auth/refresh` | POST | Público | Rotación de refresh token |
| `/auth/logout` | POST | JWT | Revoca todas las sesiones |
| `/.well-known/jwks.json` | GET | Público | Clave pública JWKS |
| `/docs` | GET | Público | Swagger UI |
| `/actuator/health` | GET | Público | Health check |
