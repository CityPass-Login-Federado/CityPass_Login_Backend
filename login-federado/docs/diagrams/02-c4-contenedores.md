# C4 — Contenedores de Identidad y Acceso CityPass+ (Nivel 2)

**Estado representado:** AS-IS.

**Sistema en foco:** Identidad y Acceso CityPass+.

```mermaid
C4Container
    title Contenedores — Identidad y Acceso CityPass+

    Person(usuario, "Persona CityPass+", "Ciudadano, delegado o administrador global")

    System_Ext(clientApps, "Aplicaciones CityPass+", "Clientes web o móviles de los seis módulos")
    System_Ext(adminWeb, "Panel administrativo web", "Cliente citypass-admin-web")
    System_Ext(platformApis, "APIs de módulos CityPass+", "Validan los JWT sin consultar al emisor en cada request")
    System_Ext(serviceClients, "Servicios backend", "Clientes de servicio registrados")

    System_Boundary(identity, "Identidad y Acceso CityPass+") {
        Container(loginApi, "Login Federado API", "Java 21, Spring Boot 3, Spring Security", "Login LDAP, JWT RS256, refresh rotativo, JWKS, client_credentials y backend del panel")
        Container(anomalyApi, "Detección de anomalías", "Python 3.12, FastAPI", "Calcula un score y una decisión ALLOW, REVIEW o BLOCK")
        ContainerDb(openldap, "Directorio de identidades", "OpenLDAP", "Personas, grupos, módulos, contraseñas y bloqueo de cuentas")
        ContainerDb(postgres, "Base relacional", "PostgreSQL 16 / Supabase en producción", "Refresh tokens, cadenas, intentos de login y panel_audit")
    }

    Rel(usuario, clientApps, "Usa")
    Rel(usuario, adminWeb, "Administra identidades con")

    Rel(clientApps, loginApi, "Login, refresh y logout", "HTTPS/JSON")
    Rel(adminWeb, loginApi, "Login y administración /panel/**", "HTTPS/JSON + JWT")
    Rel(serviceClients, loginApi, "Solicita JWT de servicio en /oauth/token", "HTTPS form + Basic Auth")
    Rel(platformApis, loginApi, "Descarga /.well-known/jwks.json", "HTTPS/JSON")

    Rel(loginApi, openldap, "Busca y autentica personas; administra el directorio", "LDAP bind/search/modify")
    Rel(loginApi, postgres, "Lee y escribe tokens, intentos y auditoría", "JDBC/SQL")
    Rel(loginApi, anomalyApi, "Solicita evaluación de riesgo; falla cerrada si no responde", "HTTP/JSON POST /score")
    Rel(anomalyApi, postgres, "Consulta intentos históricos", "SQL")
```

## Responsabilidades y límites

- `Login Federado API` es el único emisor de JWT de este sistema.
- Las APIs consumidoras descargan JWKS y validan localmente firma, expiración, issuer, audience y contrato de claims.
- `anomaly-detection` es obligatorio para el login humano: un error de red o del servicio produce un 401 genérico.
- OpenLDAP es la fuente de verdad de identidad y autorización por grupos; PostgreSQL no reemplaza al directorio.
- El contenedor one-shot `ldap-config` es operacional y se muestra en los diagramas de despliegue.
- No existe actualmente un contenedor Kafka/RabbitMQ. Los eventos se serializan en el log mediante `LoggingEventPublisher`.

## Leyenda

- **Container:** aplicación ejecutable desplegable de forma independiente.
- **ContainerDb:** almacén de datos utilizado por el sistema.
- **System_Ext:** consumidor fuera de la frontera de Identidad y Acceso.
- Cada relación entre procesos indica el protocolo o formato utilizado.
