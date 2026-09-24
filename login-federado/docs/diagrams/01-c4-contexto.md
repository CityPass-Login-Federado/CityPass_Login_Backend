# C4 — Contexto del sistema (nivel 1)

**Sistema de interés:** Módulo de Login Federado e Identidad de CityPass+
**Fecha de revisión:** 24 de septiembre de 2026

El nivel de contexto presenta personas y sistemas externos directamente relacionados con Login Federado. Los detalles de tecnología, almacenamiento y despliegue se reservan para los niveles siguientes.

```mermaid
C4Context
    title Login Federado - Contexto del sistema

    Person(user, "Persona usuaria", "Accede a una aplicación CityPass+")
    Person(delegate, "Delegado de módulo", "Administra personas y grupos de su módulo")
    Person(globalAdmin, "Administrador global", "Administra identidades de todos los módulos")

    System(login, "Login Federado", "Autentica identidades, emite tokens, administra el directorio y publica eventos de identidad")

    System_Ext(frontend, "Frontend de Login y Administración", "Interfaz web del módulo")
    System_Ext(modules, "Módulos CityPass+", "Aplicaciones y APIs que consumen la identidad federada")
    System_Ext(gateway, "CityPass Event Gateway", "Recibe eventos cuando se habilita la integración EDA HTTP")
    System_Ext(mail, "Proveedor SMTP", "Entrega enlaces de recuperación de contraseña")

    Rel(user, frontend, "Inicia sesión, consulta su perfil y administra su contraseña", "HTTPS")
    Rel(delegate, frontend, "Gestiona personas y grupos", "HTTPS")
    Rel(globalAdmin, frontend, "Realiza consultas y operaciones transversales", "HTTPS")
    Rel(frontend, login, "Consume la API REST de identidad", "HTTPS/JSON")
    Rel(modules, login, "Obtienen tokens de servicio y claves JWKS", "HTTPS")
    Rel(login, gateway, "Publica identidad.login, identidad.refresh e identidad.logout", "OAuth 2.0/HTTPS")
    Rel(gateway, login, "Obtiene token de servicio y consulta JWKS", "HTTPS")
    Rel(login, mail, "Envía enlaces de recuperación", "SMTP/TLS")
```

## Responsabilidad y límites

- Login Federado centraliza autenticación humana, sesiones renovables, identidad de servicios, perfil y administración del directorio.
- Los módulos consumidores validan JWT localmente mediante JWKS; no consultan al IdP en cada request.
- La lógica de movilidad, residuos, reclamos, emergencias, espacios, analítica y EDA queda fuera del sistema de interés.
- OpenLDAP, PostgreSQL y detección de anomalías son contenedores internos del módulo y se muestran en el nivel 2, no como sistemas externos de este contexto.
- La publicación EDA funciona en modo `logging` por defecto. La relación con el gateway solo se materializa al configurar `EDA_PUBLISHER=http` y las variables `EDA_*`.

## Correspondencia con C4

El alcance es un único sistema de software. Todos los elementos externos son personas o sistemas directamente conectados al sistema de interés; la infraestructura se representa exclusivamente en los diagramas de despliegue.
