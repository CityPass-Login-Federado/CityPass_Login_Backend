# Despliegue Cloud — Producción

**Ambiente:** producción AS-IS según los archivos versionados.

**Fuente de verdad:** GitHub Actions `CD.yml`, ambos `docker-compose.prod.yml` y la configuración Spring/FastAPI.

```mermaid
flowchart LR
    USERS["Aplicaciones y panel CityPass+"]
    APIS["APIs consumidoras de JWT"]

    subgraph GITHUB["GitHub"]
        REPO["Repositorio<br/>rama main"]
        ACTIONS["GitHub Actions CD<br/>build, push y deploy"]
        GHCR["GitHub Container Registry<br/>login-backend:latest<br/>anomaly-detection:latest"]
        REPO --> ACTIONS
        ACTIONS -->|"Docker build & push"| GHCR
    end

    subgraph OCI["Oracle Cloud · dos nodos de cómputo"]
        subgraph VM1["VM1 Backend · Docker Compose"]
            LOGIN["Instancia de contenedor<br/><b>citypass-login</b><br/>Java 21 · Spring Boot<br/>puerto publicado 8081"]
            LDAP["Instancia de contenedor<br/><b>citypass-ldap</b><br/>OpenLDAP"]
            LDAPCFG["Contenedor one-shot<br/><b>citypass-ldap-config</b><br/>overlays, ACLs y seed"]
            ENV1[".env / secretos de despliegue<br/>DB, LDAP, servicios y URL de anomalías"]
            KEYS["Filesystem de contenedor<br/>/app/keys<br/>auto-generación activa por defecto"]

            LOGIN -->|"LDAP search/bind/modify"| LDAP
            LDAPCFG -.->|configura después del healthcheck| LDAP
            ENV1 --> LOGIN
            ENV1 --> LDAPCFG
            LOGIN --- KEYS
        end

        subgraph VM2["VM2 IA · Docker Compose"]
            ANOM["Instancia de contenedor<br/><b>citypass-anomaly-detection</b><br/>Python 3.12 · FastAPI<br/>puerto publicado 8000"]
            ENV2[".env / secretos de despliegue<br/>credenciales Supabase"]
            ENV2 --> ANOM
        end
    end

    SUPA[("Supabase administrado<br/>PostgreSQL vía pooler<br/>sa-east-1 · TLS requerido")]

    ACTIONS -->|"SSH + git fetch/reset + docker compose pull/up"| VM1
    ACTIONS -->|"SSH + git fetch/reset + docker compose pull/up"| VM2
    GHCR -->|"pull imagen login"| LOGIN
    GHCR -->|"pull imagen anomalías"| ANOM

    USERS -->|"HTTP(S) hacia puerto 8081"| LOGIN
    APIS -->|"GET /.well-known/jwks.json"| LOGIN
    LOGIN -->|"ANOMALY_SERVICE_URL · HTTP(S)/JSON /score"| ANOM
    LOGIN -->|"JDBC con sslmode=require"| SUPA
    ANOM -->|"SQLAlchemy con sslmode=require"| SUPA
```

## Responsabilidades por nodo

| Nodo/servicio | Responsabilidad |
|---|---|
| GitHub Actions | Construye las dos imágenes, las publica en GHCR y ejecuta el redeploy remoto por SSH. |
| Oracle VM1 | Ejecuta Login Federado, OpenLDAP y el configurador one-shot del directorio. |
| Oracle VM2 | Ejecuta exclusivamente el servicio de detección de anomalías. |
| Supabase | Aloja PostgreSQL compartido por Login Federado y detección de anomalías. |
| GHCR | Distribuye las imágenes versionadas por rama, SHA y `latest`. |

## Fronteras y comunicaciones

- Las APIs consumidoras solo necesitan acceso al JWKS público y validan tokens localmente.
- Login Federado depende sincrónicamente de VM2 durante el login; si `/score` no responde dentro de los timeouts configurados, el login falla cerrado.
- OpenLDAP no se publica mediante `ports` en el Compose de producción y queda accesible para los contenedores de VM1.
- PostgreSQL exige TLS mediante `sslmode=require`.
- El repositorio no documenta una instancia de load balancer, reverse proxy, DNS, certificado TLS, reglas OCI ni VPN; si existen fuera del repositorio deben agregarse como nodos de infraestructura verificados.

## Riesgos y decisiones pendientes visibles en el AS-IS

1. **Claves RSA:** `docker-compose.prod.yml` no monta un volumen o secret para `/app/keys` ni desactiva `jwt.auto-generate-keys`. Una recreación del contenedor puede cambiar el par y el `kid`, invalidando access tokens todavía vigentes.
2. **Inicialización SQL:** `spring.sql.init.mode=always` sigue activo y `schema.sql` elimina y recrea `panel_audit`, `refresh_tokens` y `login_attempts` durante el arranque, incluso usando la conexión productiva de Supabase.
3. **Persistencia LDAP:** no hay volúmenes nombrados ni política de backup documentada en el Compose productivo; debe confirmarse y representarse la persistencia efectiva del directorio.
4. **Exposición de VM2:** Compose publica el puerto 8000. Debe verificarse en la infraestructura real que solo VM1 pueda acceder a `/score`, salvo que exista un motivo para exposición pública.
5. **Event Bus:** no se despliega Kafka/RabbitMQ; la publicación actual termina en logs. Un broker futuro debe incorporarse en una vista TO-BE, con autenticación, topics y política de entrega definidos.
