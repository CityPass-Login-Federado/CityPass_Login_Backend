# C4 Container Diagram — CityPass+ (Nivel 2)

Estado auditado al 14 de noviembre de 2026. Se muestran contenedores comprobados; el Event Bus figura como integración pendiente.

```mermaid
C4Container
    title Contenedores del módulo de identidad

    Person(usuario, "Usuario o delegado", "Usa login y panel")

    System_Boundary(citypass, "CityPass+ — Squad 2") {
        Container(frontend, "Login Frontend", "React 18 / TypeScript", "Login, tokens en memoria y panel")
        Container(login, "Login Federado", "Spring Boot 3.3.13 / Java 21", "LDAP, JWT, refresh y administración")
        Container(risk, "Anomaly Detection", "FastAPI / Python 3.12", "Scoring por reglas o Isolation Forest si existe modelo")
    }

    System_Ext(openldap, "OpenLDAP", "Personas, contraseñas y grupos")
    System_Ext(postgres, "PostgreSQL 16", "Refresh, intentos y auditoría")
    System_Ext(consumers, "APIs de otros squads", "Validan JWT con JWKS")
    System_Ext(eventbus, "Event Bus Squad 1", "No integrado")

    Rel(usuario, frontend, "HTTPS")
    Rel(frontend, login, "REST JSON")
    Rel(login, openldap, "Búsqueda, bind y administración")
    Rel(login, postgres, "JPA / JDBC")
    Rel(login, risk, "POST /score")
    Rel(risk, postgres, "Lee login_attempts")
    Rel(login, consumers, "JWT RS256 y JWKS")
    Rel(login, eventbus, "Pendiente; hoy solo LoggingEventPublisher")
```

## Estado de integración

- La autenticación frontend/backend coincide en login, refresh y logout.
- El panel no es interoperable todavía: el frontend usa rutas inglesas y el backend españolas.
- Sin `models/isolation_forest.pkl`, FastAPI usa reglas determinísticas.
- `usuario.autenticado` se escribe al log; no existe publicación a un broker.
