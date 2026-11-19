# UML — Casos de uso del módulo Login Federado

**Módulo:** Login Federado e Identidad — Grupo 2
**Fecha de revisión:** 19 de noviembre de 2026
**Estado:** actualizado contra el backend y las historias de usuario vigentes

Fuente original: [Diagramas UML (CU, ER) — Módulo 2](https://app.notion.com/p/drff/Diagramas-UML-CU-ER-Modulo-2-3c977c7e30048071b13dd90ecd96a45b?source=copy_link)

## 1. Convención utilizada

Mermaid no ofrece una sintaxis UML de casos de uso compatible con todos los renderizadores. Por ese motivo, los diagramas utilizan `flowchart`, actores externos, un límite explícito del sistema y nodos ovalados para los casos de uso.

- Las flechas continuas representan participación del actor.
- Las flechas discontinuas usan explícitamente los estereotipos UML `<<include>>` y `<<extend>>`. En `include` la flecha apunta al caso reutilizado; en `extend` apunta al caso base.
- Los servicios externos quedan fuera del límite del módulo.
- Los casos de uso describen comportamiento comprobable; no representan clases ni componentes internos.

## 2. Autenticación, sesión e integración

```mermaid
flowchart LR
    PERSON["Persona usuaria"]
    WEB["Aplicación web registrada"]
    SERVICE["Servicio backend"]
    API["API consumidora"]
    OPERATOR["Responsable de operación"]
    ANOMALY["Servicio de anomalías"]
    MAIL["Servicio de correo"]
    GATEWAY["Event Gateway - Grupo 1"]

    subgraph IDP["Módulo 2 - Login Federado"]
        direction TB
        LOGIN(["Iniciar sesión"])
        LOCKOUT(["Controlar intentos repetidos"])
        RISK(["Evaluar riesgo del login"])
        REFRESH(["Renovar sesión"])
        LOGOUT(["Cerrar sesión"])
        PROFILE(["Consultar perfil propio"])
        CHANGE_PASSWORD(["Cambiar contraseña propia"])
        FORGOT_PASSWORD(["Solicitar recuperación"])
        RESET_PASSWORD(["Restablecer contraseña con token"])
        REVOKE_SESSIONS(["Revocar sesiones renovables"])
        REVOKE_ACCESS(["Invalidar access previos en Login"])
        SERVICE_TOKEN(["Obtener token de servicio"])
        JWKS(["Consultar claves públicas"])
        HEALTH(["Consultar salud del servicio"])
        ENQUEUE_EVENT(["Encolar evento de identidad"])
        DELIVER_EVENT(["Entregar evento pendiente"])
    end

    PERSON --> WEB
    WEB --> LOGIN
    WEB --> REFRESH
    WEB --> LOGOUT
    WEB --> PROFILE
    WEB --> CHANGE_PASSWORD
    WEB --> FORGOT_PASSWORD
    WEB --> RESET_PASSWORD
    SERVICE --> SERVICE_TOKEN
    API --> JWKS
    OPERATOR --> HEALTH

    LOGIN -.->|"<<include>>"| LOCKOUT
    LOGIN -.->|"<<include>>"| RISK
    RISK --> ANOMALY
    FORGOT_PASSWORD --> MAIL
    CHANGE_PASSWORD -.->|"<<include>>"| REVOKE_SESSIONS
    RESET_PASSWORD -.->|"<<include>>"| REVOKE_SESSIONS
    CHANGE_PASSWORD -.->|"<<include>>"| REVOKE_ACCESS
    RESET_PASSWORD -.->|"<<include>>"| REVOKE_ACCESS
    ENQUEUE_EVENT -.->|"<<extend>> [login exitoso]"| LOGIN
    ENQUEUE_EVENT -.->|"<<extend>> [refresh exitoso]"| REFRESH
    ENQUEUE_EVENT -.->|"<<extend>> [sesión reconocida]"| LOGOUT
    DELIVER_EVENT -.->|"<<extend>> [evento PENDING]"| ENQUEUE_EVENT
    GATEWAY --> DELIVER_EVENT
```

### Trazabilidad funcional

| Caso de uso | Endpoint principal | Reglas relevantes |
| --- | --- | --- |
| Iniciar sesión | `POST /auth/login` | Exige `username`, `password` y `clientId`; autentica contra LDAP; valida cliente y módulo; emite JWT humano por 15 minutos y refresh token por 8 horas. |
| Controlar intentos repetidos | Incluido en login | Cinco fallos dentro de 15 minutos activan el bloqueo temporal; el error externo continúa siendo genérico. |
| Evaluar riesgo | `POST /score` del servicio externo | Se ejecuta después del bind LDAP; `BLOCK` rechaza y una falla del servicio aplica comportamiento cerrado. |
| Renovar sesión | `POST /auth/refresh` | Rota el refresh token, relee LDAP y revoca la cadena completa ante reúso o canje concurrente. |
| Cerrar sesión | `POST /auth/logout` | Revoca el refresh token y responde `204` aunque sea desconocido; si recibe un Bearer token, también denylista su `jti`. |
| Consultar perfil | `GET /me` | Solo acepta token humano y relee la identidad desde LDAP. |
| Cambiar contraseña | `POST /me/change-password` | Verifica la contraseña actual, revoca refresh tokens y hace que este backend rechace access tokens anteriores del sujeto. |
| Solicitar recuperación | `POST /auth/forgot-password` | Responde siempre `204`, aplica límites antiabuso y envía un enlace de un solo uso de manera asíncrona. |
| Restablecer contraseña | `POST /auth/reset-password` | Consume atómicamente el token, cambia la contraseña y revoca las sesiones renovables. |
| Obtener token de servicio | `POST /oauth/token` | Usa Client Credentials; admite Basic Auth o formulario; emite un JWT de servicio por 15 minutos, sin grupos ni refresh token. |
| Consultar claves públicas | `GET /.well-known/jwks.json` | Publica la clave RSA y el `kid` necesarios para validar JWT RS256. |
| Consultar salud | `GET /health` o `/actuator/health` | Ambos endpoints están permitidos públicamente; Actuator es el usado por despliegues y contenedores. |
| Encolar y entregar evento | Sin endpoint de usuario | Persiste `identidad.login`, `identidad.refresh` o `identidad.logout` en `event_outbox`; el relay entrega con reintentos al adaptador de logging o al Gateway. |

### Restricciones representadas

- Las fallas de credenciales, cliente, módulo y lockout utilizan una respuesta `401` genérica; el rechazo por anomalía usa `403` y su indisponibilidad `503`, sin exponer detalles de la cuenta.
- El evaluador de anomalías es una dependencia sincrónica del login; no es un actor humano.
- El correo se utiliza únicamente para entregar el enlace de recuperación. La solicitud no cambia la contraseña en LDAP.
- El gateway es un sistema externo real del Grupo 1. En ejecución aislada puede usarse el adaptador de logging; en ambos modos la autenticación queda desacoplada mediante Outbox.
- El JWT de servicio identifica al cliente por `sub` y `namespace`; nunca representa a una persona.

## 3. Panel de administración

```mermaid
flowchart LR
    DELEGATE["Delegado de módulo"]
    GLOBAL["Administrador global"]
    ANALYTICS["Backend de Analítica - Grupo 8"]

    subgraph PANEL["Panel de administración de identidad"]
        direction TB
        LIST_PERSON(["Listar y consultar personas"])
        CREATE_PERSON(["Crear persona"])
        UPDATE_PERSON(["Actualizar persona"])
        DISABLE_PERSON(["Deshabilitar persona"])
        ENABLE_PERSON(["Rehabilitar persona"])
        UNLOCK_PERSON(["Desbloquear intentos fallidos"])
        ADMIN_RESET(["Asignar contraseña temporal"])
        LIST_GROUP(["Listar grupos"])
        CREATE_GROUP(["Crear grupo"])
        DELETE_GROUP(["Eliminar grupo"])
        ADD_MEMBER(["Agregar membresía"])
        REMOVE_MEMBER(["Quitar membresía"])
        BULK_MEMBER(["Asignar membresías masivas"])
        LIST_MODULES(["Listar módulos"])
        GLOBAL_PEOPLE(["Consultar personas de todos los módulos"])
        GLOBAL_GROUPS(["Consultar grupos de todos los módulos"])
        LIST_ANOMALIES(["Consultar eventos de anomalía"])
        APPROVE_ANOMALY(["Aprobar acceso anómalo por única vez"])
        VIEW_ANALYTICS(["Consultar tablero de Analítica"])
        SELECT_MODULE(["Seleccionar módulo a operar"])
        REVOKE_ALL(["Revocar sesiones renovables"])
        AUDIT(["Registrar auditoría"])
    end

    DELEGATE --> LIST_PERSON
    DELEGATE --> CREATE_PERSON
    DELEGATE --> UPDATE_PERSON
    DELEGATE --> DISABLE_PERSON
    DELEGATE --> ENABLE_PERSON
    DELEGATE --> UNLOCK_PERSON
    DELEGATE --> ADMIN_RESET
    DELEGATE --> LIST_GROUP
    DELEGATE --> CREATE_GROUP
    DELEGATE --> DELETE_GROUP
    DELEGATE --> ADD_MEMBER
    DELEGATE --> REMOVE_MEMBER
    DELEGATE --> BULK_MEMBER
    DELEGATE --> LIST_MODULES
    DELEGATE --> LIST_ANOMALIES
    DELEGATE --> APPROVE_ANOMALY

    GLOBAL --> GLOBAL_PEOPLE
    GLOBAL --> GLOBAL_GROUPS
    GLOBAL --> LIST_MODULES
    GLOBAL --> SELECT_MODULE
    GLOBAL --> LIST_PERSON
    GLOBAL --> CREATE_PERSON
    GLOBAL --> UPDATE_PERSON
    GLOBAL --> DISABLE_PERSON
    GLOBAL --> ENABLE_PERSON
    GLOBAL --> UNLOCK_PERSON
    GLOBAL --> ADMIN_RESET
    GLOBAL --> LIST_GROUP
    GLOBAL --> CREATE_GROUP
    GLOBAL --> DELETE_GROUP
    GLOBAL --> ADD_MEMBER
    GLOBAL --> REMOVE_MEMBER
    GLOBAL --> BULK_MEMBER
    GLOBAL --> LIST_ANOMALIES
    GLOBAL --> APPROVE_ANOMALY
    GLOBAL --> VIEW_ANALYTICS
    VIEW_ANALYTICS --> ANALYTICS

    DISABLE_PERSON -.->|"<<include>>"| REVOKE_ALL
    APPROVE_ANOMALY -.->|"<<include>>"| UNLOCK_PERSON
    CREATE_PERSON -.->|"<<include>>"| AUDIT
    UPDATE_PERSON -.->|"<<include>>"| AUDIT
    DISABLE_PERSON -.->|"<<include>>"| AUDIT
    ENABLE_PERSON -.->|"<<include>>"| AUDIT
    UNLOCK_PERSON -.->|"<<include>>"| AUDIT
    ADMIN_RESET -.->|"<<include>>"| AUDIT
    CREATE_GROUP -.->|"<<include>>"| AUDIT
    DELETE_GROUP -.->|"<<include>>"| AUDIT
    ADD_MEMBER -.->|"<<include>>"| AUDIT
    REMOVE_MEMBER -.->|"<<include>>"| AUDIT
    BULK_MEMBER -.->|"<<include>>"| AUDIT
    APPROVE_ANOMALY -.->|"<<include>>"| AUDIT
```

### Trazabilidad funcional

| Caso de uso | Endpoint principal | Alcance y reglas |
| --- | --- | --- |
| Listar y consultar personas | `GET /panel/people`, `GET /panel/people/{uid}` | El delegado queda limitado al módulo del JWT; permite paginación y filtros. |
| Crear persona | `POST /panel/people` | Genera `employeeNumber` global, secuencial e inmutable; username y correo son únicos globalmente. |
| Actualizar persona | `PUT /panel/people/{uid}` | Puede renombrar el username y repara las referencias de membresía; no modifica `employeeNumber`. |
| Deshabilitar persona | `POST /panel/people/{uid}/disable` | Bloquea la cuenta y revoca los refresh tokens sin eliminar la identidad; actualmente no avanza el corte de access tokens. |
| Rehabilitar persona | `POST /panel/people/{uid}/enable` | Elimina el bloqueo conservando identidad y membresías. |
| Desbloquear intentos fallidos | `POST /panel/people/{uid}/unlock` | Elimina el lockout temporal y los intentos fallidos; no aprueba por sí solo una anomalía. |
| Asignar contraseña temporal | `POST /panel/people/{uid}/reset-password` | Cambia la contraseña en LDAP; todavía no revoca refresh tokens ni fuerza de forma verificable el cambio posterior. |
| Listar y crear grupos | `GET /panel/groups`, `POST /panel/groups` | Los grupos pertenecen a un módulo; nombre de hasta 64 caracteres en minúsculas, números y guiones. |
| Eliminar grupo | `DELETE /panel/groups/{name}` | El delegado no puede eliminar `delegados`; el código permite hacerlo a `admin-global`, decisión pendiente de validar. |
| Agregar o quitar membresía | Rutas `/panel/groups/{name}/members` | Solo personas del mismo módulo; advertencia desde 30 grupos y máximo de 50 por persona. |
| Asignar membresías masivas | `POST /panel/group-memberships/bulk` | Producto cartesiano deduplicado, hasta 1.000 relaciones; informa resultado total y por relación. |
| Listar módulos | `GET /panel/modules` | Devuelve los siete módulos habilitados actualmente. |
| Consultas globales | `GET /panel/admin/people`, `GET /panel/admin/groups` | Exclusivas de `admin-global`; agregan, filtran y paginan datos de todos los módulos. |
| Consultar y aprobar anomalías | `GET /panel/anomaly-events`, `POST /panel/anomaly-events/{id}/approve` | Respeta el módulo del delegado; la aprobación dura 15 minutos, coincide por contexto y se consume una sola vez. |
| Consultar tablero de Analítica | `GET /admin/analitica` | Exclusivo de `admin-global`; actúa como proxy autenticado del HTML entregado por el Grupo 8. |

### Reglas de autorización y coherencia

- El panel requiere un JWT humano con audiencia `citypass-admin-api`, `ver=1` y grupo `delegados` o `admin-global`.
- El delegado normal opera exclusivamente el módulo del claim `module`; un parámetro externo no amplía ese alcance.
- El administrador global debe indicar `?module=` para realizar operaciones sobre una persona o un grupo concreto.
- No existe un caso de uso para eliminar personas: la baja se implementa como deshabilitación lógica.
- La auditoría es de mejor esfuerzo en el estado actual. Una falla de persistencia se registra en logs, pero no revierte automáticamente la operación LDAP.
- La protección que impide eliminar o vaciar el grupo `delegados` se aplica al delegado normal, no al administrador global; por eso no se modela como garantía universal.

## 4. Correspondencia con las historias de usuario

| Grupo de casos de uso | Historias relacionadas |
| --- | --- |
| Login, refresh, logout, perfil y contraseñas | HU-AUT-01 a HU-AUT-06 |
| Token de servicio y JWKS | HU-AUT-07 y HU-AUT-08 |
| Bloqueo y detección de anomalías | HU-SEG-01 y HU-SEG-02 |
| Administración de personas | HU-PER-01 a HU-PER-07 |
| Administración de grupos | HU-GRP-01 a HU-GRP-04 |
| Auditoría, eventos y salud | HU-OPS-01 a HU-OPS-03 |

## 5. Correcciones respecto del diagrama original

- Se incorporaron perfil, cambio y recuperación de contraseña, JWKS, salud y publicación de eventos.
- Se separaron los clientes humanos de los clientes de servicio.
- Se agregó el servicio de anomalías como dependencia del login.
- Se incorporaron actualización, rehabilitación, reset administrativo y consultas globales de personas.
- Se incorporaron eliminación de grupos, remoción de miembros y asignación masiva.
- Se representó la revocación de sesiones al deshabilitar una persona.
- Se incorporaron el desbloqueo, la revisión/aprobación de anomalías y el proxy del tablero de Analítica.
- Se reemplazó la publicación directa por el guardado y relay del Outbox.
- Se normalizaron las relaciones con `<<include>>` y `<<extend>>`.
- Se eliminó el caso ambiguo de “gestionar grupo” y se lo descompuso en operaciones verificables.
- Se documentaron las excepciones reales de `admin-global`, la auditoría de mejor esfuerzo y la integración EDA condicionada.
