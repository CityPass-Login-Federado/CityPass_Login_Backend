# Actualización de Historias de Usuario — Módulo Login Federado

**Equipo:** Grupo 2  
**Módulo:** Login Federado e Identidad  
**Fecha de revisión:** 23 de septiembre de 2026

**Estado del documento:** alineado con el comportamiento verificable del repositorio

## 1. Objetivo y alcance

Este documento actualiza las historias de usuario del módulo de Login Federado de CityPass+. Conserva el alcance funcional del documento original y lo amplía para reflejar las capacidades que actualmente expone el backend.

El módulo se considera funcional de manera aislada. Las integraciones con otros equipos se describen como contratos o capacidades configurables y no se presentan como una plataforma integrada cuando dependen de infraestructura externa.

Fuentes de contraste:

- Versión Preliminar [Historias de Usuario — Módulo Login Federado (Grupo 2)](https://docs.google.com/document/d/1FQzHB9xOVBVCQvRlG1a0tGIaJqr7uuwq3CMyFRul_xM/edit?tab=t.0)
- Código fuente, configuración, pruebas automatizadas y esquema de datos de `login-federado`
- Contratos y decisiones de arquitectura incluidos en `docs/adr`
- Contrato y guía de integración incluidos en `src/main/java/citypass/loginfederado/event`

## 2. Estados utilizados

| Estado | Significado |
| --- | --- |
| Implementada | El flujo principal y sus reglas de aceptación están presentes en el backend. |
| Implementada con observaciones | El flujo funciona, pero existe una diferencia documental o una condición que debe aclararse. |
| Parcial | Existe una implementación, pero falta una garantía necesaria para considerar completa la historia. |
| Condicionada a integración | El módulo está preparado, pero la ejecución completa depende de otro sistema o equipo. |

## 3. Actores

- **Persona usuaria:** integrante de CityPass+ que utiliza un cliente web registrado para acceder a un módulo.
- **Aplicación web:** cliente de tipo `human` que solicita tokens para una audiencia determinada.
- **Delegado de módulo:** persona perteneciente al grupo `delegados`, autorizada a administrar identidades y grupos de un único módulo.
- **Administrador global:** persona perteneciente al grupo `admin-global`, autorizada a consultar todos los módulos y operar el módulo indicado expresamente.
- **Servicio backend:** cliente de tipo `service` que se autentica mediante Client Credentials y no representa a una persona.
- **API consumidora:** servicio que valida localmente los JWT emitidos por el proveedor de identidad.
- **Responsable de seguridad y auditoría:** rol que necesita trazabilidad de accesos, cambios administrativos y eventos de identidad.
- **Gateway de eventos:** componente externo que recibe eventos de identidad cuando se habilita la integración HTTP.

## 4. Trazabilidad con el documento original

| Historia original | Historia actual | Resultado de la actualización |
| --- | --- | --- |
| US-1.1 — Inicio de sesión | HU-AUT-01 | Se conserva y se agregan cliente, módulo, bloqueo, anomalías, claims y evento. |
| US-1.2 — Renovación | HU-AUT-02 | Se conserva y se detallan hash, rotación, revalidación y reúso concurrente. |
| US-1.3 — Cierre de sesión | HU-AUT-03 | Se conserva y se aclaran la respuesta idempotente y la vigencia del access token. |
| US-1.4 — Client Credentials | HU-AUT-07 | Se conserva y se corrige la vigencia a 15 minutos y las formas de autenticación admitidas. |
| US-2.1 — Alta de personas | HU-PER-02 | Se conserva y se precisan formato, unicidad y alcance por módulo. |
| US-2.2 — Alta de grupos | HU-GRP-01 | Se conserva y se agregan reglas de reserva, longitud y aislamiento. |
| US-2.3 — Asignación de grupos | HU-GRP-03 y HU-GRP-04 | Se conserva y se amplía con remoción y asignación masiva. |
| US-2.4 — Deshabilitación | HU-PER-04 | Se conserva y se agrega revocación de sesiones renovables y auditoría. |

## 5. Épica AUT — Autenticación y ciclo de sesión

### HU-AUT-01 — Iniciar sesión en un módulo

**Historia.** Como persona usuaria, quiero iniciar sesión con mis credenciales y el cliente de la aplicación para acceder de forma segura al módulo que me corresponde.

**Estado:** Implementada.

**Criterios de aceptación:**

- `POST /auth/login` recibe obligatoriamente `username`, `password` y `clientId`.
- La aplicación debe estar registrada como cliente humano y su módulo debe ser compatible con la pertenencia de la persona en LDAP. El cliente administrativo transversal puede aceptar personas de cualquiera de los módulos registrados.
- Una contraseña vacía se rechaza antes de intentar el bind contra LDAP.
- Un inicio válido consulta el servicio de detección de anomalías y, si es aceptado, devuelve un access token JWT RS256 de 15 minutos y un refresh token opaco de 8 horas.
- El JWT humano utiliza `employeeNumber` como `sub` e incluye `aud`, `iss`, `jti`, `token_use=human`, `ver=1`, `preferred_username`, `module` y `groups`.
- Usuario inexistente, contraseña incorrecta, cliente inválido, módulo incompatible, cuenta bloqueada, riesgo bloqueado o indisponibilidad del evaluador de riesgo responden con el mismo error genérico `401`.
- Un inicio exitoso publica el evento `identidad.login`.

**Evidencia principal:** `AuthController`, `AuthService`, `AccessTokenIssuer`, `ClientRegistry`, `LoginAttemptService` y `AuthServiceTest`.

### HU-AUT-02 — Renovar una sesión

**Historia.** Como aplicación web, quiero renovar la sesión mediante el refresh token para mantener el acceso sin solicitar nuevamente las credenciales.

**Estado:** Implementada.

**Criterios de aceptación:**

- `POST /auth/refresh` recibe un refresh token no vacío.
- El valor crudo del token no se almacena: su búsqueda se realiza mediante hash SHA-256.
- Cada canje revoca el eslabón utilizado y emite un nuevo access token y un nuevo refresh token dentro de la misma cadena.
- Antes de emitir se relee LDAP para verificar que la cuenta siga habilitada, actualizar sus grupos y confirmar que el cliente continúe aceptando el módulo de la persona.
- También se valida que la audiencia persistida coincida con el registro actual del cliente.
- Un token desconocido, vencido o revocado responde `401`.
- El reúso, incluido el canje concurrente del mismo token, revoca la cadena completa.
- Una renovación exitosa publica `identidad.refresh`.

**Evidencia principal:** `RefreshTokenService`, `AuthService`, `RefreshTokenServiceTest` y `AuthServiceTest`.

### HU-AUT-03 — Cerrar una sesión

**Historia.** Como persona usuaria, quiero cerrar mi sesión para impedir nuevas renovaciones con el refresh token utilizado.

**Estado:** Implementada.

**Criterios de aceptación:**

- `POST /auth/logout` recibe el refresh token en el cuerpo y no exige un access token vigente.
- El refresh token reconocido queda revocado de forma persistente.
- Un token vacío o desconocido también devuelve `204`, sin revelar si existía.
- Un logout válido publica `identidad.logout`.
- El access token ya emitido no se revoca de forma inmediata y puede seguir siendo válido hasta completar sus 15 minutos.

**Evidencia principal:** `AuthController`, `RefreshTokenService` y `RefreshTokenServiceTest`.

### HU-AUT-04 — Consultar el perfil propio

**Historia.** Como persona autenticada, quiero consultar mi perfil para visualizar mis datos y permisos vigentes.

**Estado:** Implementada.

**Criterios de aceptación:**

- `GET /me` requiere un JWT válido con `token_use=human`.
- La identidad se resuelve por el `sub` estable y los datos se releen de LDAP en cada llamada.
- La respuesta contiene username, identificador, nombre completo, correo, módulo y grupos.
- Un token de servicio recibe `403`.
- Una cuenta inexistente o deshabilitada después de la emisión del token recibe `404`.

**Evidencia principal:** `ProfileController`, `PasswordService` y `ProfileControllerTest`.

### HU-AUT-05 — Cambiar la contraseña propia

**Historia.** Como persona autenticada, quiero cambiar mi contraseña acreditando la vigente para proteger mi cuenta.

**Estado:** Implementada.

**Criterios de aceptación:**

- `POST /me/change-password` requiere un JWT humano, la contraseña actual y una nueva contraseña de al menos ocho caracteres.
- La contraseña actual se valida mediante bind LDAP antes de realizar el cambio.
- Al cambiar la contraseña se revocan todos los refresh tokens activos de la persona.
- La operación devuelve `204`; los access tokens previos conservan su vencimiento natural.

**Evidencia principal:** `ProfileController`, `PasswordService`, `PanelAccountService` y `PasswordServiceTest`.

### HU-AUT-06 — Recuperar una contraseña olvidada

**Historia.** Como persona que perdió su contraseña, quiero recibir un enlace de recuperación de un solo uso para restablecer el acceso sin intervención de un delegado.

**Estado:** Implementada con observaciones operativas.

**Criterios de aceptación:**

- `POST /auth/forgot-password` responde siempre `204`, exista o no la cuenta y se aplique o no un límite antiabuso.
- La solicitud aplica un enfriamiento por cuenta y límites por cuenta e IP.
- El token de recuperación es aleatorio, se persiste únicamente como hash, tiene una vigencia configurable de 30 minutos y reemplaza cualquier token activo anterior.
- El envío del enlace se realiza de manera asíncrona. Si la entrega falla, el token pendiente se libera y la contraseña vigente permanece sin cambios.
- `POST /auth/reset-password` acepta un token de un solo uso y una contraseña nueva de al menos ocho caracteres.
- Antes del canje se revalida la cuenta en LDAP; un token inexistente, usado, vencido o perteneciente a una cuenta deshabilitada devuelve el mismo `422`.
- Un canje exitoso cambia la contraseña y revoca todos los refresh tokens de la cuenta.
- La entrega real por correo requiere configuración SMTP; en desarrollo existe un modo de registro controlado por configuración.

**Evidencia principal:** `AuthController`, `PasswordService`, `PasswordResetTokenStore`, `PasswordResetLimiter` y sus pruebas unitarias.

### HU-AUT-07 — Obtener un token de servicio

**Historia.** Como servicio backend, quiero autenticarme con mis propias credenciales para consumir recursos o publicar eventos sin reutilizar la identidad de una persona.

**Estado:** Implementada.

**Criterios de aceptación:**

- `POST /oauth/token` consume `application/x-www-form-urlencoded` y solo admite `grant_type=client_credentials`.
- Las credenciales pueden enviarse mediante Basic Auth o mediante `client_id` y `client_secret` en el formulario.
- Solo se aceptan clientes registrados como `service`.
- La respuesta incluye un JWT de servicio con vigencia de 15 minutos y no emite refresh token.
- El JWT incluye `sub=client_id`, `namespace`, `aud=["citypass"]`, `iss`, `jti`, `iat`, `exp`, `token_use=service` y `ver=1`; no contiene `groups` ni `module`.
- Los errores siguen el formato OAuth con `error` y `error_description`.

**Evidencia principal:** `OAuthTokenController`, `AccessTokenIssuer`, `ClientRegistry` y `OAuthTokenControllerTest`.

### HU-AUT-08 — Publicar claves de validación

**Historia.** Como API consumidora, quiero obtener las claves públicas del proveedor de identidad para validar localmente los JWT sin compartir la clave privada.

**Estado:** Implementada.

**Criterios de aceptación:**

- `GET /.well-known/jwks.json` es público y devuelve la clave RSA pública en formato JWKS.
- El `kid` publicado coincide con el encabezado de los tokens y se deriva de la clave mediante huella RFC 7638.
- La clave privada no forma parte de la respuesta.

**Evidencia principal:** `JwksController`, `JwtKeyConfig`, `AccessTokenIssuer` y `JwtKeyConfigTest`.

## 6. Épica SEG — Protección y evaluación de riesgo

### HU-SEG-01 — Bloquear intentos repetidos

**Historia.** Como responsable de seguridad, quiero limitar los intentos fallidos de autenticación para reducir ataques de fuerza bruta.

**Estado:** Implementada.

**Criterios de aceptación:**

- Cada intento registra usuario, dirección IP, agente de usuario, resultado y momento de ejecución.
- Cinco fallos dentro de una ventana deslizante de 15 minutos bloquean nuevos intentos para ese usuario durante la ventana aplicable.
- El bloqueo devuelve el mismo `401` que las demás fallas de autenticación.
- La IP considera el primer valor de `X-Forwarded-For` cuando la aplicación opera detrás de un proxy.

**Evidencia principal:** `LoginAttemptService`, `AuthService`, `LoginAttemptServiceTest` y ADR-004.

### HU-SEG-02 — Evaluar anomalías de inicio de sesión

**Historia.** Como responsable de seguridad, quiero evaluar el riesgo de un inicio de sesión válido para bloquear accesos anómalos antes de emitir tokens.

**Estado:** Parcial.

**Criterios de aceptación cubiertos:**

- La evaluación se ejecuta después de validar las credenciales LDAP y antes de emitir tokens.
- La solicitud al evaluador incluye username, IP, agente de usuario, fecha y resultado de autenticación.
- Una decisión `BLOCK` rechaza el login con el error genérico `401`.
- Si el servicio no responde dentro de sus tiempos de conexión y lectura, el login falla de forma cerrada.

**Criterio pendiente:**

- Una decisión `REVIEW` permite continuar, pero aún no queda persistida como señal de revisión en los intentos de login. El propio código identifica esta mejora como pendiente.

**Evidencia principal:** `AnomalyRiskClient`, `AuthService`, `AnomalyRiskClientTest` y ADR-009.

## 7. Épica PER — Administración de personas

Todas las operaciones de `/panel/**` requieren un JWT con audiencia `citypass-admin-api`, `token_use=human`, `ver=1` y pertenencia a `delegados` o `admin-global`.

### HU-PER-01 — Consultar personas del módulo

**Historia.** Como delegado, quiero listar y consultar personas de mi módulo para administrar identidades sin acceder a otros dominios.

**Estado:** Implementada.

**Criterios de aceptación:**

- `GET /panel/people` devuelve resultados paginados y admite filtros por texto, grupo y estado de habilitación.
- `GET /panel/people/{uid}` devuelve la ficha de una persona del módulo autorizado.
- Un delegado normal opera exclusivamente el módulo de su claim; no puede cambiar el alcance mediante `?module=`.
- Una búsqueda no expone personas de otros módulos.

**Evidencia principal:** `PanelController`, `PanelAuthorization`, `PanelPersonService` y sus pruebas.

### HU-PER-02 — Crear una persona

**Historia.** Como delegado, quiero crear una persona dentro de mi módulo para habilitar su acceso a CityPass+.

**Estado:** Implementada.

**Criterios de aceptación:**

- `POST /panel/people` recibe nombre, apellido, username, correo y contraseña inicial.
- El sistema asigna un `employeeNumber` global, secuencial e inmutable con formato `U` seguido de seis dígitos.
- Username y correo son únicos en todo CityPass+.
- El username admite entre 3 y 32 caracteres, con minúsculas, números, punto, guion bajo y guion; la contraseña inicial exige al menos ocho caracteres.
- La persona se crea en la unidad organizativa LDAP del módulo autorizado.
- La operación queda registrada en la auditoría del panel.

**Evidencia principal:** `PanelController`, `PanelPersonService`, `PanelDirectoryRules` y `PanelPersonServiceTest`.

### HU-PER-03 — Actualizar una persona

**Historia.** Como delegado, quiero corregir los datos de una persona para mantener actualizada su identidad sin modificar su identificador estable.

**Estado:** Implementada.

**Criterios de aceptación:**

- `PUT /panel/people/{uid}` permite actualizar nombre, apellido, correo y username.
- `employeeNumber` no puede modificarse.
- El correo y el nuevo username conservan sus reglas de validación y unicidad global.
- Cuando cambia el username, se renombra la entrada LDAP y se reparan las referencias de membresía de los grupos.
- La operación queda registrada en la auditoría.

**Evidencia principal:** `PanelPersonService`, `PanelLdapSupport` y `PanelPersonServiceTest`.

### HU-PER-04 — Deshabilitar una persona

**Historia.** Como delegado, quiero deshabilitar una persona sin borrar su identidad para bloquear el acceso y conservar su historial.

**Estado:** Implementada.

**Criterios de aceptación:**

- `POST /panel/people/{uid}/disable` aplica el bloqueo de cuenta en LDAP.
- La ficha, el identificador y las membresías se conservan; no existe un endpoint para eliminar personas.
- La operación revoca todos los refresh tokens activos de la persona.
- La baja y la revocación se registran en la auditoría del panel.

**Evidencia principal:** `PanelController`, `PanelAccountService`, `RefreshTokenService` y `PanelAccountServiceTest`.

### HU-PER-05 — Rehabilitar una persona

**Historia.** Como delegado, quiero rehabilitar una persona para restablecer su acceso sin recrear su identidad.

**Estado:** Implementada.

**Criterios de aceptación:**

- `POST /panel/people/{uid}/enable` elimina el bloqueo de cuenta en LDAP.
- El identificador, los datos personales y las membresías se conservan.
- La operación queda registrada en la auditoría.

**Evidencia principal:** `PanelAccountService` y `PanelAccountServiceTest`.

### HU-PER-06 — Restablecer una contraseña desde el panel

**Historia.** Como delegado, quiero asignar una contraseña temporal a una persona para ayudarla a recuperar el acceso.

**Estado:** Parcial.

**Criterios de aceptación cubiertos:**

- `POST /panel/people/{uid}/reset-password` exige una contraseña temporal de al menos ocho caracteres.
- La contraseña se actualiza en LDAP y la operación queda auditada sin almacenar su valor.

**Criterios pendientes de definición o implementación:**

- El backend no marca de forma verificable la obligación de cambiar la contraseña en el siguiente ingreso, aunque la descripción del endpoint lo expresa como comportamiento esperado.
- Este flujo administrativo no revoca actualmente los refresh tokens existentes de la persona. Debe definirse si se equiparará con el cambio y el recupero autoservicio.

**Evidencia principal:** `PanelController`, `PanelAccountService` y `PanelAccountServiceTest`.

### HU-PER-07 — Administrar identidades globalmente

**Historia.** Como administrador global, quiero consultar y operar los módulos de CityPass+ para resolver tareas transversales de identidad.

**Estado:** Implementada con observaciones documentales.

**Criterios de aceptación:**

- El JWT debe incluir el grupo `admin-global` y la audiencia del panel.
- `GET /panel/admin/people` y `GET /panel/admin/groups` agregan resultados de todos los módulos, identificando el módulo de cada fila y aplicando paginación y filtros.
- Para crear, modificar o actuar sobre una persona o grupo, el administrador global debe indicar un `?module=` válido.
- `GET /panel/modules` devuelve los siete módulos actuales: `movilidad`, `residuos`, `reclamos`, `emergencias`, `espacios`, `analitica` y `eda`.
- Un delegado normal continúa aislado en el módulo declarado por su token.

**Observación:** algunas descripciones internas todavía mencionan seis módulos, pero la constante operativa y la configuración contienen siete.

**Evidencia principal:** `PanelAuthorization`, `PanelController`, `PanelDirectoryRules` y `PanelControllerTest`.

## 8. Épica GRP — Administración de grupos y permisos

### HU-GRP-01 — Consultar y crear grupos

**Historia.** Como delegado, quiero consultar y crear grupos de mi módulo para organizar los permisos de acceso.

**Estado:** Implementada.

**Criterios de aceptación:**

- `GET /panel/groups` devuelve una lista paginada y permite filtrar por nombre y condición de reservado.
- `POST /panel/groups` crea un grupo únicamente dentro del módulo autorizado.
- El nombre admite hasta 64 caracteres en minúsculas, números y guiones, sin guion inicial, final ni consecutivo.
- Los grupos se modelan como `groupOfNames`; la API solo agrega personas del mismo módulo y no permite anidamiento de grupos.
- El grupo `delegados` se identifica como reservado.
- Las operaciones de creación quedan auditadas.

**Evidencia principal:** `PanelController`, `PanelGroupService`, `PanelDirectoryRules` y `PanelGroupServiceTest`.

### HU-GRP-02 — Eliminar un grupo

**Historia.** Como delegado, quiero eliminar un grupo que dejó de utilizarse para mantener ordenada la estructura de permisos.

**Estado:** Parcial.

**Criterios de aceptación cubiertos:**

- `DELETE /panel/groups/{name}` elimina un grupo existente del módulo autorizado y devuelve `204`.
- La operación queda auditada.
- Un delegado normal no puede eliminar el grupo reservado `delegados`.

**Criterio pendiente:**

- El código permite que un administrador global elimine `delegados`. Si el carácter reservado debe ser una invariancia global, esta excepción debe eliminarse o documentarse como una decisión explícita.

**Evidencia principal:** `PanelGroupService` y `PanelGroupServiceTest`.

### HU-GRP-03 — Administrar membresías individuales

**Historia.** Como delegado, quiero agregar o quitar personas de grupos para conceder y retirar permisos específicos.

**Estado:** Implementada con una observación de seguridad.

**Criterios de aceptación:**

- `POST /panel/groups/{name}/members` agrega una persona existente del mismo módulo y rechaza identidades de otros módulos o grupos anidados.
- Se emite una advertencia desde las 30 membresías y se bloquea una nueva asignación cuando la persona ya alcanzó 50 grupos.
- `DELETE /panel/groups/{name}/members/{uid}` quita una membresía existente.
- Las altas y bajas de membresía quedan auditadas.

**Observación:** un delegado normal no puede dejar `delegados` sin integrantes; actualmente el administrador global está exceptuado de esa protección. Esta excepción debe validarse como decisión de seguridad.

**Evidencia principal:** `PanelGroupService`, `PanelDirectoryRules`, `PanelGroupServiceTest` y `PanelDirectoryRulesTest`.

### HU-GRP-04 — Asignar membresías en forma masiva

**Historia.** Como delegado, quiero asignar varias personas a varios grupos en una sola operación para reducir tareas repetitivas de administración.

**Estado:** Implementada.

**Criterios de aceptación:**

- `POST /panel/group-memberships/bulk` recibe listas de usernames y grupos, normaliza espacios y elimina duplicados.
- La operación aplica el producto cartesiano de personas y grupos con un máximo de 1.000 relaciones por solicitud.
- Antes de escribir se validan la existencia de personas y grupos y el máximo proyectado de 50 membresías por persona.
- Cada grupo se actualiza de forma atómica, sin afirmar una transacción distribuida entre grupos distintos.
- La respuesta informa estado `SUCCESS`, `PARTIAL` o `FAILED`, cantidades, resultados por relación y advertencias.
- Cada membresía creada queda auditada.

**Evidencia principal:** `PanelController`, `PanelGroupService`, DTO de membresías masivas y `PanelGroupServiceTest`.

## 9. Épica OPS — Auditoría, eventos y operación

### HU-OPS-01 — Auditar cambios administrativos

**Historia.** Como responsable de seguridad, quiero conocer quién realizó cada cambio administrativo para disponer de trazabilidad operativa.

**Estado:** Parcial.

**Criterios de aceptación cubiertos:**

- Las mutaciones de personas, cuentas, contraseñas administrativas, grupos y membresías intentan registrar actor, módulo, acción, destino, detalle y fecha en `panel_audit`.
- Las contraseñas y secretos no se incluyen en el detalle de auditoría.
- Una falla al persistir la auditoría queda registrada como error operativo.

**Criterio pendiente:**

- La implementación captura el error de auditoría y permite que la operación continúe. Por lo tanto, no existe garantía de auditoría obligatoria ni una transacción única entre LDAP y PostgreSQL. El criterio debe definirse y la documentación técnica debe dejar de afirmar que una falla de auditoría bloquea la mutación.

**Evidencia principal:** `PanelAuditService`, `PanelAuditEntry` y `PanelAuditServiceTest`.

### HU-OPS-02 — Publicar eventos de identidad

**Historia.** Como equipo consumidor, quiero recibir eventos de login, renovación y logout para integrar el módulo de identidad con la arquitectura orientada a eventos.

**Estado:** Condicionada a integración.

**Criterios de aceptación:**

- Los flujos exitosos publican `identidad.login`, `identidad.refresh` e `identidad.logout`.
- El evento utiliza un envelope versionado con `type`, `version`, `occurredAt`, `metadata` y `data`.
- La identidad humana viaja como `data.actorSub`; el token humano no se reenvía al gateway.
- El publicador HTTP obtiene un token propio de servicio y envía el evento al endpoint configurado.
- El modo predeterminado registra el envelope en logs, lo que permite probar el módulo de manera aislada.
- La entrega real depende de configurar `EDA_PUBLISHER=http`, la URL del gateway, credenciales y el path acordado con el equipo responsable.

**Dependencia externa:** el repositorio no contiene el broker ni define por sí solo las políticas finales de reintento, disponibilidad y confirmación del gateway.

**Evidencia principal:** `EventPublisher`, `LoggingEventPublisher`, `EdaHttpEventPublisher`, `EdaEventEnvelopeFactory`, `Guia_EDA.md` y sus pruebas.

### HU-OPS-03 — Verificar disponibilidad del módulo

**Historia.** Como responsable de operación, quiero consultar un endpoint de salud para verificar que el servicio de identidad está disponible.

**Estado:** Parcial.

**Criterios de aceptación cubiertos:**

- `GET /health` existe y devuelve un estado simple del servicio.
- La indisponibilidad de SMTP no marca la aplicación como no saludable, porque el envío de recuperación es asíncrono y dispone de manejo propio de fallas.
- El contrato HTTP del módulo se expone mediante OpenAPI para facilitar pruebas e integración.

**Criterio pendiente:**

- La guía EDA presenta `GET /health` como público, pero `SecurityConfig` no incluye actualmente esa ruta entre los endpoints públicos. Sin JWT, la ruta protegida es `/health`; el endpoint público configurado es `/actuator/health`. Debe corregirse la configuración o unificarse el contrato documental.

**Evidencia principal:** `HealthController`, `SecurityConfig`, `OpenApiConfig`, `HealthControllerTest` y `OpenApiConfigTest`.

## 10. Reglas transversales

- LDAP es la fuente de verdad de identidades, contraseñas, estado de cuenta y membresías.
- PostgreSQL conserva refresh tokens, intentos de autenticación, tokens de recuperación y auditoría administrativa.
- `sub` identifica de forma estable a una persona mediante `employeeNumber`; el username puede cambiar.
- Los access tokens se validan sin sesión de servidor y expiran por tiempo. La revocación inmediata se aplica a credenciales renovables.
- Los módulos vigentes son `movilidad`, `residuos`, `reclamos`, `emergencias`, `espacios`, `analitica` y `eda`.
- Los clientes humanos reciben la audiencia de su API; los servicios utilizan audiencia `citypass` y un `namespace` propio.
- Los errores de autenticación humana no revelan si existe un usuario, si está bloqueado, si pertenece a otro módulo o si fue rechazado por riesgo.
- Los datos y parámetros utilizados en filtros LDAP deben escaparse; las reglas de formato y aislamiento se validan antes de modificar el directorio.

## 11. Matriz resumida de verificación

| Área | Historias | Pruebas principales |
| --- | --- | --- |
| Login y sesión | HU-AUT-01 a HU-AUT-03 | `AuthControllerTest`, `AuthServiceTest`, `RefreshTokenServiceTest`, `LoginAttemptServiceTest` |
| Perfil y contraseñas | HU-AUT-04 a HU-AUT-06 | `ProfileControllerTest`, `PasswordServiceTest`, `PasswordResetTokenStoreTest`, `PasswordResetLimiterTest`, `PasswordEmailServiceTest` |
| Token de servicio y JWKS | HU-AUT-07 y HU-AUT-08 | `OAuthTokenControllerTest`, `AccessTokenIssuerTest`, `JwtKeyConfigTest`, `SecurityConfigTest` |
| Seguridad y anomalías | HU-SEG-01 y HU-SEG-02 | `LoginAttemptServiceTest`, `AnomalyRiskClientTest`, `GlobalExceptionHandlerTest` |
| Personas | HU-PER-01 a HU-PER-07 | `PanelControllerTest`, `PanelAuthorizationTest`, `PanelPersonServiceTest`, `PanelAccountServiceTest`, `PanelLdapSupportTest` |
| Grupos | HU-GRP-01 a HU-GRP-04 | `PanelControllerTest`, `PanelGroupServiceTest`, `PanelDirectoryRulesTest` |
| Auditoría y eventos | HU-OPS-01 a HU-OPS-03 | `PanelAuditServiceTest`, `EdaEventEnvelopeFactoryTest`, `EdaHttpEventPublisherTest`, `LoggingEventPublisherTest`, `HealthControllerTest` |


## 12. Criterio de cierre documental

Una historia se considera cerrada cuando sus criterios de aceptación coinciden con el contrato publicado, el comportamiento del código y al menos una prueba automatizada relevante. Las capacidades externas se consideran cerradas únicamente cuando la integración puede verificarse contra el sistema real y no solo mediante el modo local de registro.
