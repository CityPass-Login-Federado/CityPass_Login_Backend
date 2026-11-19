# C4 — Contenedores (nivel 2)

**Sistema de interés:** Módulo de Login Federado e Identidad de CityPass+
**Fecha de revisión:** 19 de noviembre de 2026

En C4, un contenedor es una aplicación o almacén de datos ejecutable de manera independiente. Este diagrama representa la arquitectura lógica de ejecución y no una topología cloud específica.

```mermaid
C4Container
    title Login Federado - Contenedores y dependencias

    Person(user, "Persona usuaria", "Utiliza una aplicación CityPass+")
    Person(admin, "Delegado o administrador global", "Gestiona identidades y grupos")

    System_Ext(frontend, "Frontend de Login y Administración", "React, TypeScript y navegador web")
    System_Ext(modules, "Módulos consumidores", "APIs independientes de CityPass+")
    System_Ext(gateway, "Event Gateway - Grupo 1", "Ingreso real a la plataforma EDA")
    System_Ext(analytics, "Backend de Analítica - Grupo 8", "Tablero HTML de Identidad")
    System_Ext(mail, "Proveedor SMTP", "Servicio de correo")

    System_Boundary(loginSystem, "Login Federado") {
        Container(api, "API de Identidad + Relay de Outbox", "Java 21 y Spring Boot 3", "Login, revocación de tokens, perfil, panel, proxy de Analítica y relay EDA programado")
        Container(risk, "Servicio de Anomalías", "Python 3.12 y FastAPI", "Construye señales y devuelve ALLOW, REVIEW o BLOCK")
        ContainerDb(ldap, "Directorio de Identidades", "OpenLDAP", "Personas, credenciales, grupos y membresías")
        ContainerDb(db, "Persistencia Operativa", "PostgreSQL", "Sesiones, lockouts, anomalías, revocaciones, auditoría, recuperación y event_outbox")
    }

    Rel(user, frontend, "Utiliza", "HTTPS")
    Rel(admin, frontend, "Utiliza", "HTTPS")
    Rel(frontend, api, "Login, sesión, perfil y panel", "HTTPS/JSON")
    Rel(modules, api, "Solicitan client_credentials y consultan JWKS", "HTTPS")
    Rel(api, ldap, "Busca, autentica y administra identidades", "LDAP")
    Rel(api, db, "Lee y persiste estado operativo", "JDBC/TLS")
    Rel(api, risk, "Evalúa el riesgo de un login LDAP válido", "HTTP/JSON")
    Rel(risk, db, "Consulta historial de intentos", "SQL/TLS")
    Rel(api, mail, "Envía enlaces de recuperación", "SMTP/TLS")
    Rel(api, gateway, "El relay entrega eventos pendientes con reintentos cuando el adaptador HTTP está activo", "OAuth 2.0/HTTPS")
    Rel(gateway, api, "Solicita token de servicio y consulta JWKS", "HTTPS")
    Rel(api, analytics, "Reenvía el JWT humano y devuelve el tablero HTML", "HTTPS")
```

## Decisiones y límites

- OpenLDAP es la fuente de verdad de identidades, credenciales, estado de cuenta y membresías.
- PostgreSQL conserva once tablas operativas: refresh tokens, intentos, lockouts, evaluaciones y bloqueos de anomalías, revocaciones de access tokens, cortes de revocación por sujeto, auditoría, recuperación y Outbox; no almacena contraseñas.
- El servicio FastAPI consulta `login_attempts` y es una dependencia sincrónica del login. La API aplica fallo cerrado cuando no puede evaluarse el riesgo.
- La API persiste los eventos de login, refresh y logout en `event_outbox`. `EventOutboxRelay`, ejecutado dentro del mismo proceso Spring Boot, reclama filas con `SKIP LOCKED` y aplica reintentos con *backoff*; no es un contenedor desplegable separado.
- El proxy de Analítica solo está disponible para `admin-global`; el frontend nunca consume directamente el backend externo.
- El pipeline `anomaly-detection/ML` es una capacidad de desarrollo offline, no un contenedor runtime; se representa en la vista de desarrollo 4+1.
- La topología, las réplicas, los volúmenes y los nodos cloud se documentan en los diagramas de despliegue 12 y 13.
