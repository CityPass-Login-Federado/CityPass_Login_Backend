# C4 Container Diagram — CityPass+ (Nivel 2)

```mermaid
C4Container
    title Diagrama de Contenedores — CityPass+ Plataforma

    Person(ciudadano, "Ciudadano", "Usuario de la app")

    System_Boundary(citypass, "CityPass+ Platform") {
        Container(login, "Login Federado", "Spring Boot / Java 21", "Autenticación LDAP + JWT RS256")
        Container(movilidad, "Movilidad Urbana", "Spring Boot", "Gestión de bicis, estacionamientos")
        Container(residuos, "Gestión de Residuos", "Spring Boot", "Sensores, recolección")
        Container(reclamos, "Reclamos", "Spring Boot", "Alta, seguimiento de reclamos")
        Container(emergencias, "Emergencias", "Spring Boot", "Botón de pánico, alertas")
        Container(espacios, "Espacios Públicos", "Spring Boot", "Reservas, eventos")
        Container(analitica, "Analítica IA/ML", "Spring Boot", "Dashboards, predicciones")
        Container(eda, "Event Bus Module", "Grupo 1", "Contratos, topics, políticas")
    }

    System_Ext(openldap, "OpenLDAP", "Directorio de identidades")
    System_Ext(postgres, "PostgreSQL", "Base de datos")

    Rel(ciudadano, login, "POST /auth/login")
    Rel(login, openldap, "LDAP bind authentication")
    Rel(login, postgres, "Persiste refresh_tokens, login_attempts")
    Rel(login, eda, "Publica usuario.autenticado")
    Rel(movilidad, login, "Valida JWT vía JWKS")
    Rel(residuos, login, "Valida JWT vía JWKS")
    Rel(reclamos, login, "Valida JWT vía JWKS")
    Rel(emergencias, login, "Valida JWT vía JWKS")
    Rel(espacios, login, "Valida JWT vía JWKS")
    Rel(analitica, login, "Valida JWT vía JWKS")
```

## Descripción

El módulo **Login Federado** es el único punto de autenticación para toda la plataforma. Los otros 6 módulos validan JWTs offline descargando la clave pública del endpoint `/.well-known/jwks.json` — no necesitan llamar a este servicio en cada request.
