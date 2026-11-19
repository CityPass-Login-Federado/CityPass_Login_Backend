# C4 — Contexto del sistema (nivel 1)

**Sistema de interés:** Módulo de Login Federado e Identidad de CityPass+
**Fecha de revisión:** 19 de noviembre de 2026

El nivel de contexto presenta personas y sistemas externos directamente relacionados con Login Federado. Los detalles de tecnología, almacenamiento y despliegue se reservan para los niveles siguientes.

```mermaid
C4Context
    title Login Federado - Contexto del sistema

    Person(user, "Persona usuaria", "Accede a una aplicación CityPass+")
    Person(delegate, "Delegado de módulo", "Administra personas y grupos de su módulo")
    Person(globalAdmin, "Administrador global", "Administra identidades de todos los módulos")

    System(login, "Login Federado", "Autentica identidades, emite y revoca tokens, administra el directorio, publica eventos y protege el acceso a Analítica")

    System_Ext(frontend, "Frontend de Login y Administración", "Interfaz web del módulo")
    System_Ext(modules, "Módulos CityPass+", "Aplicaciones y APIs que consumen la identidad federada")
    System_Ext(gateway, "Event Gateway - Grupo 1", "Recibe eventos de identidad de forma autenticada")
    System_Ext(analytics, "Backend de Analítica - Grupo 8", "Entrega el tablero HTML de Identidad")
    System_Ext(mail, "Proveedor SMTP", "Entrega enlaces de recuperación de contraseña")

    Rel(user, frontend, "Inicia sesión, consulta su perfil y administra su contraseña", "HTTPS")
    Rel(delegate, frontend, "Gestiona personas y grupos", "HTTPS")
    Rel(globalAdmin, frontend, "Realiza consultas y operaciones transversales", "HTTPS")
    Rel(frontend, login, "Consume la API REST de identidad", "HTTPS/JSON")
    Rel(modules, login, "Obtienen tokens de servicio y claves JWKS", "HTTPS")
    Rel(login, gateway, "Publica identidad.login, identidad.refresh e identidad.logout", "OAuth 2.0/HTTPS")
    Rel(gateway, login, "Obtiene token de servicio y consulta JWKS", "HTTPS")
    Rel(login, analytics, "Proxy autenticado del tablero; reenvía el JWT humano", "HTTPS/text-html")
    Rel(login, mail, "Envía enlaces de recuperación", "SMTP/TLS")
```

## Responsabilidad y límites

- Login Federado centraliza autenticación humana, sesiones renovables, identidad de servicios, perfil y administración del directorio.
- Los módulos consumidores validan JWT localmente mediante JWKS; no consultan al IdP en cada request.
- La lógica de movilidad, residuos, reclamos, emergencias, espacios, Analítica y EDA queda fuera del sistema de interés. Login solo integra sus contratos de identidad, eventos y tablero.
- OpenLDAP, PostgreSQL y detección de anomalías son contenedores internos del módulo y se muestran en el nivel 2, no como sistemas externos de este contexto.
- Los eventos se guardan primero en `event_outbox`; un relay programado los entrega al Gateway o al adaptador de logging con reintentos y *backoff*. La salida HTTP se habilita con `EDA_PUBLISHER=http` y las variables `EDA_*`.
- `GET /admin/analitica` exige `admin-global`, reenvía el Bearer token al backend de Analítica y propaga su HTML y encabezados permitidos.

## Correspondencia con C4

El alcance es un único sistema de software. Todos los elementos externos son personas o sistemas directamente conectados al sistema de interés; la infraestructura se representa exclusivamente en los diagramas de despliegue.
