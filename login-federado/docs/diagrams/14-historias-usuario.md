# Historias de Usuario - Módulo Login Federado (Grupo 2)

Versión corregida y alineada con el backend actual de `login-federado`.

Fuentes de contraste:

- [Documento original de historias de usuario](https://docs.google.com/document/d/1FQzHB9xOVBVCQvRlG1a0tGIaJqr7uuwq3CMyFRul_xM/edit?tab=t.0)
- Código fuente, configuración y esquema SQL del módulo
- Diagramas técnicos `01` a `13` de esta carpeta

## Alcance y actores

- **Persona usuaria:** inicia y mantiene una sesión humana desde una aplicación CityPass+ registrada.
- **Aplicación web:** cliente humano registrado que envía `clientId` y recibe tokens para una audiencia concreta.
- **Delegado de módulo:** persona con grupo `delegados`; administra únicamente el módulo indicado por el claim `module`.
- **Administrador global:** persona con grupo `admin-global`; puede operar cualquiera de los seis módulos indicando `?module=`.
- **Servicio backend:** cliente de tipo `service` que usa Client Credentials y no representa a una persona.

## Épica 1 - Autenticación y autorización

| ID | Historia de usuario | Criterios de aceptación verificables |
| --- | --- | --- |
| US-1.1 | Como persona usuaria, quiero iniciar sesión con mi usuario y contraseña para acceder al módulo que me corresponde. | `POST /auth/login` recibe `username`, `password` y `clientId`. Los tres campos son obligatorios. Una contraseña vacía se rechaza antes de consultar LDAP. El cliente debe existir, ser humano y aceptar el módulo de la persona. El backend autentica con bind LDAP, consulta el servicio de anomalías y falla de forma cerrada si no puede evaluarlo. Un login exitoso emite un JWT RS256 de 15 minutos y un refresh token opaco de 8 horas. Los fallos de credenciales, usuario, módulo, bloqueo o riesgo responden `401` con un mensaje genérico. Los intentos se registran con usuario, IP, agente, resultado y fecha. Cinco fallos dentro de 15 minutos bloquean nuevos intentos en esa ventana. |
| US-1.2 | Como aplicación web, quiero renovar una sesión para evitar que la persona tenga que ingresar sus credenciales cada 15 minutos. | `POST /auth/refresh` recibe un refresh token no vacío. El token se busca por su hash SHA-256, nunca por su valor crudo. Cada canje revoca el eslabón usado y emite otro dentro de la misma cadena. Antes de emitir se relee LDAP para comprobar que la persona siga habilitada, obtener sus grupos actuales y validar nuevamente el módulo del cliente. Un token vencido, desconocido o revocado responde `401`. El reúso o canje concurrente de un token ya consumido revoca toda la cadena. |
| US-1.3 | Como persona usuaria, quiero cerrar mi sesión para impedir nuevas renovaciones. | `POST /auth/logout` recibe el refresh token y no exige access token. Revoca y persiste únicamente ese refresh token. Un token desconocido también responde `204` para no revelar su existencia. El access token ya emitido no se revoca y puede seguir siendo válido hasta completar sus 15 minutos. |
| US-1.4 | Como servicio backend, quiero obtener una credencial propia para invocar servicios sin reutilizar la identidad de una persona. | `POST /oauth/token` consume `application/x-www-form-urlencoded`, exige `grant_type=client_credentials` y autentica `client_id:client_secret` mediante Basic Auth. Solo admite clientes configurados como `service`. Emite un JWT de 60 minutos con `sub=client_id`, `token_use=service`, `namespace`, `aud` y `ver=1`. No incluye `groups`, `module` ni refresh token. |
| US-1.5 | Como API consumidora, quiero obtener las claves públicas del IdP para validar localmente los JWT. | `GET /.well-known/jwks.json` es público y devuelve la clave RSA pública en formato JWKS. El `kid` coincide con el usado al firmar los tokens y se deriva de la clave según RFC 7638. La clave privada nunca se publica. |

## Épica 2 - Administración de personas

Todas las operaciones de `/panel/**` exigen un JWT con audiencia `citypass-admin-api`, `token_use=human`, `ver=1` y el rol `delegados` o `admin-global`.

| ID | Historia de usuario | Criterios de aceptación verificables |
| --- | --- | --- |
| US-2.1 | Como delegado, quiero listar y consultar personas de mi módulo para administrarlas sin acceder a otros módulos. | `GET /panel/people` pagina y permite filtrar por texto, grupo y estado; `GET /panel/people/{uid}` devuelve una ficha. Un delegado normal opera siempre el módulo del claim `module` y no puede cambiarlo mediante parámetros. |
| US-2.2 | Como delegado, quiero crear una persona para incorporarla a mi módulo. | `POST /panel/people` recibe nombre, apellido, username, email y contraseña temporal. El sistema asigna un `employeeNumber` global, secuencial e inmutable con formato `U` y seis dígitos. Username y email son únicos globalmente. La persona se crea bajo `ou=People` del módulo del delegado. |
| US-2.3 | Como delegado, quiero corregir los datos de una persona sin alterar su identidad estable. | `PUT /panel/people/{uid}` permite cambiar nombre, apellido, email y username. `employeeNumber` no puede cambiar. Si cambia el username, se renombra la entrada LDAP y se reparan las referencias de membresía. Se preservan las reglas de unicidad global. |
| US-2.4 | Como delegado, quiero deshabilitar una persona sin borrar su ficha ni su historial. | `POST /panel/people/{uid}/disable` establece `pwdAccountLockedTime`, conserva la entrada LDAP y revoca todos sus refresh tokens activos. No existe un endpoint para eliminar personas. La operación queda auditada. |
| US-2.5 | Como delegado, quiero rehabilitar una persona para restablecer su acceso conservando sus datos y grupos. | `POST /panel/people/{uid}/enable` elimina el bloqueo `pwdAccountLockedTime`. La identidad, el `employeeNumber` y las membresías permanecen intactos. La operación queda auditada. |
| US-2.6 | Como delegado, quiero asignar una contraseña temporal para recuperar el acceso de una persona. | `POST /panel/people/{uid}/reset-password` recibe una contraseña temporal válida, actualiza `userPassword` en LDAP y fuerza su cambio al ingresar según la política de contraseñas. La operación queda auditada y la contraseña no se almacena en la auditoría. |
| US-2.7 | Como administrador global, quiero elegir explícitamente un módulo para operar sobre cualquiera de los seis dominios. | El JWT debe contener el grupo `admin-global`. Los endpoints de personas y grupos exigen un `?module=` válido para este rol. `GET /panel/modules` devuelve los módulos permitidos. Un delegado normal ignora `?module=` y conserva el aislamiento de su claim. |

## Épica 3 - Administración de grupos y permisos

| ID | Historia de usuario | Criterios de aceptación verificables |
| --- | --- | --- |
| US-3.1 | Como delegado, quiero listar los grupos de mi módulo para conocer la estructura de permisos disponible. | `GET /panel/groups` pagina y permite filtrar por nombre y por condición de reservado. Solo consulta `ou=Groups` del módulo autorizado. El grupo `delegados` se informa con `reserved=true`. |
| US-3.2 | Como delegado, quiero crear grupos para organizar permisos del módulo. | `POST /panel/groups` acepta nombres de hasta 64 caracteres formados por minúsculas, números y guiones, sin guiones iniciales, finales ni consecutivos. El nombre debe ser único dentro del módulo. `delegados` es reservado. El grupo se crea como `groupOfNames` y no se admiten grupos anidados. |
| US-3.3 | Como delegado, quiero eliminar un grupo que ya no se utiliza. | `DELETE /panel/groups/{name}` elimina un grupo del módulo y responde `204`. El grupo reservado `delegados` no puede eliminarse. La operación queda auditada. |
| US-3.4 | Como delegado, quiero agregar una persona a un grupo para concederle permisos. | `POST /panel/groups/{name}/members` recibe `memberUid`. Solo acepta personas del mismo módulo, no grupos. La respuesta advierte desde 30 membresías y bloquea una nueva asignación cuando la persona ya tiene 50 grupos. La operación queda auditada. |
| US-3.5 | Como delegado, quiero quitar una persona de un grupo para retirar permisos. | `DELETE /panel/groups/{name}/members/{uid}` elimina la membresía y devuelve el estado resultante. El grupo `delegados` nunca puede quedar sin miembros. La operación queda auditada. |

## Épica 4 - Auditoría, eventos y métricas

| ID | Historia de usuario | Criterios de aceptación verificables |
| --- | --- | --- |
| US-4.1 | Como responsable de seguridad, quiero que las mutaciones del panel queden auditadas para conocer quién cambió qué y en qué módulo. | Cada alta, modificación, habilitación, deshabilitación, cambio de contraseña y mutación de grupos registra `actor_sub`, `actor_uid`, `module`, `action`, `target`, `detail` y `occurred_at` en `panel_audit`. No se guardan secretos ni contraseñas. |
| US-4.2 | Como equipo de Analítica, quiero recibir métricas diarias de autenticación para observar uso y duración de sesiones. | A las 00:05 UTC se calculan las métricas del día anterior: DAU, MAU, logins exitosos por hora y cantidad/duración de sesiones cerradas. El evento se envía mediante la abstracción `EventPublisher`. En el estado actual, `LoggingEventPublisher` solo lo serializa en logs; la integración con el broker real continúa pendiente. |
| US-4.3 | Como equipo consumidor de eventos, quiero identificar correctamente a personas y servicios para no mezclar identidades. | El login humano genera `usuario.autenticado` con `sub`, `uid`, módulo y grupos. Los servicios usan su propio token y `namespace`; un token humano no se reenvía al bus. En el estado actual, los eventos se registran en logs hasta disponer del contrato y adaptador del broker. |

## Reglas transversales

- `sub` de una persona es siempre su `employeeNumber`, no su username.
- Los access tokens humanos contienen `token_use=human`, `ver=1`, `module`, `groups`, `preferred_username`, `aud`, `iss`, `iat`, `exp` y `jti`.
- Los datos de identidad y membresía viven en LDAP; PostgreSQL conserva sesiones renovables, intentos de login y auditoría.
- Las relaciones entre LDAP, PostgreSQL y la configuración de clientes son referencias lógicas, no claves foráneas físicas.
- El backend es stateless respecto del access token; la revocación inmediata se aplica a refresh tokens y el access token expira por tiempo.
