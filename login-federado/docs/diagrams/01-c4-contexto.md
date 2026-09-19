# C4 — Contexto del sistema de Identidad y Acceso CityPass+ (Nivel 1)

**Estado representado:** AS-IS del repositorio backend.

**Alcance:** autenticación de personas y servicios, administración del directorio y evaluación de riesgo.

**Fuera de alcance:** despliegue cloud y Event Bus futuro; se documentan por separado.

```mermaid
C4Context
    title Contexto del sistema — Identidad y Acceso CityPass+

    Person(ciudadano, "Ciudadano", "Persona que accede a un módulo de CityPass+")
    Person(delegado, "Delegado de módulo", "Administra personas y grupos de su módulo")
    Person(adminGlobal, "Administrador global", "Administra cualquiera de los seis módulos")

    System(identity, "Identidad y Acceso CityPass+", "Autentica personas y servicios, emite JWT RS256, administra identidades y evalúa riesgo de login")

    System_Ext(clientApps, "Aplicaciones CityPass+", "Clientes web o móviles que solicitan tokens para personas")
    System_Ext(adminWeb, "Panel administrativo web", "Cliente transversal para delegados y administradores globales")
    System_Ext(platformApis, "APIs de módulos CityPass+", "Movilidad, Residuos, Reclamos, Emergencias, Espacios y Analítica")
    System_Ext(serviceClients, "Servicios backend CityPass+", "Clientes registrados que solicitan tokens de servicio")

    System_Ext(openldap, "Directorio OpenLDAP", "Fuente de verdad de personas, grupos, módulos y estado de las cuentas")
    System_Ext(postgres, "PostgreSQL", "Refresh tokens, intentos de login y auditoría del panel")
    System_Ext(anomaly, "Detección de anomalías", "Servicio FastAPI que puntúa el riesgo de cada login")

    Rel(ciudadano, clientApps, "Usa")
    Rel(delegado, adminWeb, "Administra su módulo con")
    Rel(adminGlobal, adminWeb, "Administra cualquier módulo con")

    Rel(clientApps, identity, "Inicia sesión, renueva y cierra sesión", "HTTPS/JSON")
    Rel(adminWeb, identity, "Inicia sesión y usa /panel/**", "HTTPS/JSON + JWT")
    Rel(serviceClients, identity, "Solicita tokens client_credentials", "HTTPS form + Basic Auth")
    Rel(platformApis, identity, "Descarga la clave pública JWKS", "HTTPS/JSON")

    Rel(identity, openldap, "Busca, autentica y administra identidades", "LDAP bind/search/modify")
    Rel(identity, postgres, "Persiste tokens, intentos y auditoría", "JDBC/SQL")
    Rel(identity, anomaly, "Evalúa el riesgo antes de emitir tokens", "HTTP/JSON POST /score")
    Rel(anomaly, postgres, "Consulta historial para construir features", "SQL")
```

## Leyenda

- **Persona:** rol humano que usa CityPass+.
- **Sistema:** software dentro del alcance de este repositorio.
- **Sistema externo:** cliente o dependencia con una responsabilidad independiente.
- Las flechas indican una dependencia unidireccional y especifican el protocolo cuando corresponde.

## Aclaraciones de alcance

- Las APIs de los seis módulos son consumidores del servicio de identidad, no contenedores implementados en este repositorio.
- El Event Bus no aparece en el estado actual porque `LoggingEventPublisher` solo escribe eventos en el log. Cuando exista un broker real debe incorporarse en una vista TO-BE separada.
- Oracle Cloud, GHCR, Supabase y las VMs pertenecen a los diagramas de despliegue, no al contexto funcional.
