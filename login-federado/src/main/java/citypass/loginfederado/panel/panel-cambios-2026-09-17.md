# Cambios del módulo panel

## Resumen

Se consolidó la lógica del panel en una sola entrada HTTP, `PanelController`, con separación clara entre:

- autorización del delegado (`PanelAuthorization`),
- manipulación del directorio LDAP (`PanelDirectoryService`),
- auditoría de acciones (`PanelAuditService`).

La intención del cambio es dejar el panel como un módulo de administración seguro, con validación del JWT, control de módulos y reglas de negocio en el directorio antes de ejecutar cambios.

## Endpoints actuales

### Personas

- `GET /panel/people`
  - Lista personas del módulo que administra el delegado.
  - Soporta `module` opcional cuando el usuario es global.

- `GET /panel/people/{uid}`
  - Recupera una persona por UID del módulo activo.

- `POST /panel/people`
  - Crea una persona nueva.
  - Valida username, email, contraseña temporal y unicidad global.

- `PUT /panel/people/{uid}`
  - Actualiza nombre, apellido, email y/o renombra el UID.
  - Repara membresías de grupos afectados por el cambio.

- `GET /panel/people/all`
  - Solo para administrador global.
  - Devuelve todas las personas de todos los módulos, o un módulo concreto si se envía `module`.

- `POST /panel/people/{uid}/disable`
  - Baja lógica en lugar de borrar la identidad.
  - Bloquea la cuenta y revoca refresh tokens activos.

- `POST /panel/people/{uid}/enable`
  - Rehabilita la cuenta si estaba deshabilitada.

- `POST /panel/people/{uid}/reset-password`
  - Resetea la contraseña con una temporal válida.

### Grupos

- `GET /panel/groups`
  - Lista grupos del módulo actual.

- `POST /panel/groups`
  - Crea un grupo validando el nombre según reglas de formato.

- `DELETE /panel/groups/{name}`
  - Elimina un grupo, salvo el reservado `delegados`.

- `POST /panel/groups/{name}/members`
  - Añade una persona a un grupo.
  - Devuelve la respuesta con avisos cuando el grupo se acerca al límite.

- `DELETE /panel/groups/{name}/members/{uid}`
  - Elimina una persona del grupo, con limitaciones sobre el grupo reservado.

## Cambios funcionales clave

### 1. Control de acceso centralizado
`PanelAuthorization` valida que el JWT:

- tenga audience del panel,
- sea un token humano con la versión de contrato soportada,
- contenga los grupos `delegados` o `admin-global`,
- tenga `module` si no es administrador global.

Además, el módulo se normaliza a minúsculas para evitar inconsistencias entre el token y LDAP.

### 2. Reglas de negocio en directorio
`PanelDirectoryService` incorpora validaciones estrictas:

- módulos soportados fijos,
- nombres de grupo con formato D6,
- usernames con formato permitido,
- emails y contraseñas con criterios mínimos,
- control de duplicados globales,
- bloqueo de grupos reservados,
- advertencias al acercarse al límite de 30 grupos y bloqueo a 50.

### 3. Seguridad y auditoría
Se añadieron medidas defensivas para evitar borrados físicos:

- deshabilitar persona no elimina el registro,
- revoca refresh tokens activos,
- registra eventos de auditoría para creación, actualización, baja, reactivación y cambios de pertenencia.

### 4. API clara para panel administrativo
Los DTOs del paquete `panel.dto` permiten respuestas de negocio explícitas:

- `PersonView`
- `GlobalPersonView`
- `GroupView`
- `MembershipChangeResponse`
- `NewPersonRequest`
- `UpdatePersonRequest`
- `PasswordResetRequest`

Esto deja la API del panel más descriptiva y menos acoplada a la estructura interna del LDAP.

## Validación realizada

Se ejecutaron las pruebas unitarias del módulo panel con Maven:

```bash
mvn -Dtest=PanelAuthorizationTest,PanelDirectoryServiceTest test
```

Resultado verificado: la ejecución finalizó correctamente con salida de éxito del comando, validando la autorización y la lógica del directorio asociada a los endpoints del panel.

## Consideraciones

El diseño actual prioriza robustez y seguridad sobre flexibilidad:

- no se admite seleccionar módulos arbitrarios desde el cliente,
- la administración global queda restringida a usuarios explícitos,
- las reglas de negocio se aplican tanto en la capa HTTP como en el directorio LDAP.

Esto reduce el riesgo de operaciones no autorizadas o inconsistencias entre módulos.
