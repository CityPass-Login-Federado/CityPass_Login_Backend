# C4 Context Diagram — CityPass+ (Nivel 1)

```mermaid
C4Context
    title Diagrama de Contexto — CityPass+ Plataforma

    Person(ciudadano, "Ciudadano", "Usuario de la app CityPass+")
    Person(admin, "Administrador", "Administrador municipal")

    System(citypass, "CityPass+", "Plataforma de servicios urbanos inteligentes")

    System_Ext(openldap, "OpenLDAP", "Directorio de identidades federadas")
    System_Ext(postgres, "PostgreSQL", "Base de datos relacional")
    System_Ext(eventbus, "Event Bus", "Kafka/RabbitMQ — mensajería asincrónica")
    System_Ext(cloud, "Cloud Provider", "Infraestructura de despliegue (AWS/GCP/Azure)")

    Rel(ciudadano, citypass, "Usa la app móvil/web")
    Rel(admin, citypass, "Gestiona la plataforma")

    Rel(citypass, openldap, "Autentica usuarios (LDAP bind)")
    Rel(citypass, postgres, "Persiste tokens, intentos de login")
    Rel(citypass, eventbus, "Publica/suscribe eventos")
    Rel(citypass, cloud, "Desplegado en")
```

## Descripción

Este diagrama muestra CityPass+ como sistema central que interactúa con:
- **Ciudadanos y administradores** como usuarios humanos
- **OpenLDAP** como fuente de verdad de identidades
- **PostgreSQL** para persistencia de tokens y auditoría
- **Event Bus** para comunicación asincrónica con otros módulos
- **Cloud Provider** como infraestructura de ejecución
