# C4 — Contenedores (nivel 2)

**Sistema de interés:** Módulo de Login Federado e Identidad de CityPass+
**Fecha de revisión:** 24 de septiembre de 2026

En C4, un contenedor es una aplicación o almacén de datos ejecutable de manera independiente. Este diagrama representa la arquitectura lógica de ejecución y no una topología cloud específica.

```mermaid
C4Container
    title Login Federado - Contenedores y dependencias

    Person(user, "Persona usuaria", "Utiliza una aplicación CityPass+")
    Person(admin, "Delegado o administrador global", "Gestiona identidades y grupos")

    System_Ext(frontend, "Frontend de Login y Administración", "React, TypeScript y navegador web")
    System_Ext(modules, "Módulos consumidores", "APIs independientes de CityPass+")
    System_Ext(gateway, "CityPass Event Gateway", "Ingreso a la plataforma EDA")
    System_Ext(mail, "Proveedor SMTP", "Servicio de correo")

    System_Boundary(loginSystem, "Login Federado") {
        Container(api, "API de Identidad", "Java 21 y Spring Boot 3", "Login, tokens, perfil, panel, recuperación, auditoría y eventos")
        Container(risk, "Servicio de Anomalías", "Python 3.12 y FastAPI", "Construye señales y devuelve ALLOW, REVIEW o BLOCK")
        ContainerDb(ldap, "Directorio de Identidades", "OpenLDAP", "Personas, credenciales, grupos y membresías")
        ContainerDb(db, "Persistencia Operativa", "PostgreSQL", "Sesiones, intentos, auditoría y recuperación")
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
    Rel(api, gateway, "Publica envelopes EDA cuando el adaptador HTTP está activo", "OAuth 2.0/HTTPS")
    Rel(gateway, api, "Solicita token de servicio y consulta JWKS", "HTTPS")
```

## Decisiones y límites

- OpenLDAP es la fuente de verdad de identidades, credenciales, estado de cuenta y membresías.
- PostgreSQL conserva refresh tokens, intentos, auditoría y tokens o solicitudes de recuperación; no almacena contraseñas.
- El servicio FastAPI consulta `login_attempts` y es una dependencia sincrónica del login. La API aplica fallo cerrado cuando no puede evaluarse el riesgo.
- La imagen runtime de anomalías busca `anomaly-detection/models/isolation_forest.pkl`. El artefacto experimental está en `anomaly-detection/ML/models/`, por lo que el runtime desplegado utiliza reglas mientras no exista un modelo en la ruta empaquetada.
- El pipeline `anomaly-detection/ML` es una capacidad de desarrollo offline, no un contenedor runtime; se representa en la vista de desarrollo 4+1.
- La topología, las réplicas, los volúmenes y los nodos cloud se documentan en los diagramas de despliegue 12 y 13.
