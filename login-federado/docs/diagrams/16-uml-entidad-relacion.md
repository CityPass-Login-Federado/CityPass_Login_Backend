# Modelo entidad–relación — LDAP, PostgreSQL y configuración

**Módulo:** Login Federado e Identidad — Grupo 2
**Fecha de revisión:** 19 de noviembre de 2026
**Estado:** actualizado contra `schema.sql`, entidades JPA, estructura LDAP y configuración de clientes

Fuente original: [Diagramas UML (CU, ER) — Módulo 2](https://app.notion.com/p/drff/Diagramas-UML-CU-ER-Modulo-2-3c977c7e30048071b13dd90ecd96a45b?source=copy_link)

## 1. Alcance del modelo

El módulo utiliza tres orígenes de datos diferentes:

- **LDAP:** identidades, contraseñas, estado de cuenta, módulos y grupos.
- **PostgreSQL:** diez tablas para sesiones, intentos y lockouts, anomalías bloqueadas, revocación de access tokens, auditoría, recuperación de contraseña y Outbox EDA.
- **Configuración:** registro de clientes humanos y de servicio cargado desde `application.yml` y variables de entorno.

Las relaciones entre distintos orígenes son lógicas. No existen claves foráneas físicas entre LDAP, PostgreSQL y la configuración.

## 2. Diagrama consolidado

```mermaid
erDiagram
    LDAP_MODULE ||--o{ LDAP_PERSON : contains
    LDAP_MODULE ||--o{ LDAP_GROUP : contains
    LDAP_PERSON }o--o{ LDAP_GROUP : member
    LDAP_PERSON ||--o{ REFRESH_TOKEN : owns_logically
    LDAP_PERSON ||--o| PASSWORD_RESET_TOKEN : owns_logically
    LDAP_PERSON ||--o{ PANEL_AUDIT : acts_logically
    LDAP_PERSON ||--o{ ANOMALY_BLOCKED_EVENT : affected_logically
    LDAP_PERSON ||--o{ REVOKED_ACCESS_TOKEN : owns_logically
    LDAP_PERSON ||--o| REVOKED_SUBJECT_CUTOFF : has_logically
    CLIENT_CONFIG ||--o{ REFRESH_TOKEN : issued_for
    CLIENT_CONFIG ||--o{ ANOMALY_BLOCKED_EVENT : evaluated_for

    LDAP_MODULE {
        string ou PK
        string people_ou
        string groups_ou
    }

    LDAP_PERSON {
        string dn PK
        string employeeNumber UK
        string uid UK
        string cn
        string sn
        string givenName
        string mail UK
        string userPassword
        string pwdAccountLockedTime
        string memberOf
    }

    LDAP_GROUP {
        string dn PK
        string cn
        string member
    }

    REFRESH_TOKEN {
        uuid id PK
        string sub
        uuid chain_id
        string client_id
        string audience
        string token_hash UK
        datetime issued_at
        datetime expires_at
        datetime revoked_at
    }

    LOGIN_ATTEMPT {
        uuid id PK
        string username
        string ip_address
        string user_agent
        boolean successful
        datetime attempted_at
    }

    LOGIN_LOCKOUT {
        string username PK
        datetime locked_until
    }

    ANOMALY_BLOCKED_EVENT {
        uuid id PK
        string user_sub
        string username
        string module
        string client_id
        string ip_address
        string user_agent
        double risk_score
        text reasons
        string status
        datetime blocked_at
        datetime approved_at
        datetime approval_expires_at
        string approved_by_sub
        string approved_by_uid
        datetime consumed_at
        long version
    }

    EVENT_OUTBOX {
        uuid id PK
        string event_type
        json payload
        string status
        int attempts
        datetime next_attempt_at
        datetime created_at
        datetime sent_at
        string last_error
    }

    PANEL_AUDIT {
        uuid id PK
        string actor_sub
        string actor_uid
        string module
        string action
        string target
        string detail
        datetime occurred_at
    }

    PASSWORD_RESET_TOKEN {
        uuid id PK
        string sub
        string uid
        string token_hash UK
        datetime requested_at
        datetime expires_at
        datetime used_at
    }

    PASSWORD_RESET_REQUEST {
        uuid id PK
        string uid
        string ip_address
        datetime requested_at
    }

    REVOKED_ACCESS_TOKEN {
        string jti PK
        string sub
        datetime expires_at
    }

    REVOKED_SUBJECT_CUTOFF {
        string sub PK
        datetime revoked_before
        datetime expires_at
    }

    CLIENT_CONFIG {
        string client_id PK
        string client_secret
        string audience
        string module
        boolean transversal
        string type
        string namespace
    }
```

## 3. Interpretación de las entidades

### Directorio LDAP

#### `LDAP_MODULE`

Representa cada unidad organizativa funcional. Los módulos vigentes son `movilidad`, `residuos`, `reclamos`, `emergencias`, `espacios`, `analitica` y `eda`. Cada módulo contiene las OUs `People` y `Groups`.

#### `LDAP_PERSON`

- `dn` identifica la entrada dentro del directorio.
- `employeeNumber` tiene formato `U` seguido de seis dígitos, es único globalmente y se utiliza como `sub` estable de los JWT humanos.
- `uid` es el username y puede cambiar; el backend repara las referencias de membresía después de un renombre.
- `mail` y `uid` son únicos a nivel global.
- `userPassword` es gestionado por LDAP y su política de contraseñas; el backend no persiste la contraseña en PostgreSQL.
- `pwdAccountLockedTime` representa la deshabilitación de la cuenta.
- `memberOf` es un atributo operacional mantenido por el overlay de OpenLDAP, no la fuente primaria de la relación.

#### `LDAP_GROUP`

- `cn` es único dentro de su módulo, no globalmente.
- `member` contiene DN de personas y constituye la fuente de la membresía.
- El módulo se deriva del DN y de la OU que contiene el grupo; no existe un atributo `module` persistido en la entrada.
- La API impide el anidamiento y limita cada persona a 50 grupos.

### PostgreSQL

#### `REFRESH_TOKEN`

- Conserva únicamente el hash SHA-256 del token opaco.
- `sub` referencia de manera lógica a `LDAP_PERSON.employeeNumber`.
- Todos los eslabones de una sesión comparten `chain_id`.
- `client_id` y `audience` permiten renovar para el mismo cliente y la misma API.
- `revoked_at` nulo significa token activo; no existe una columna booleana `revoked`.
- No almacena grupos, roles, correo ni contraseña. Los grupos se releen desde LDAP en cada renovación.

#### `LOGIN_ATTEMPT`

- Registra intentos exitosos y fallidos para el bloqueo por ventana deslizante.
- `username` no posee relación obligatoria con LDAP porque también se registran nombres inexistentes.
- Actualmente no existe una columna para persistir la decisión de anomalías `REVIEW`.

#### `LOGIN_LOCKOUT`

- Materializa el bloqueo temporal por username cuando se alcanza el umbral de fallos.
- `locked_until` permite rechazar el intento sin volver a contar toda la ventana y la fila se elimina al vencer o cuando un administrador desbloquea la cuenta.
- No tiene relación obligatoria con LDAP: los intentos contra usernames inexistentes también deben ser limitados.

#### `ANOMALY_BLOCKED_EVENT`

- Persiste cada decisión `BLOCK` con el sujeto, módulo, cliente, contexto, puntaje y razones devueltos por el servicio de anomalías.
- Su estado avanza de `BLOCKED` a `APPROVED` y luego a `CONSUMED`. La aprobación tiene vencimiento, queda asociada al administrador y habilita un solo login con el mismo contexto.
- `version` implementa control optimista; el listado y la aprobación se exponen por el panel con aislamiento por módulo.

#### `EVENT_OUTBOX`

- Guarda los eventos `identidad.login`, `identidad.refresh` e `identidad.logout` antes de intentar su entrega.
- El `id` coincide con `authEventId`, de modo que el Gateway puede deduplicar reintentos.
- El relay reclama filas `PENDING` vencidas con `FOR UPDATE SKIP LOCKED`, actualiza `next_attempt_at` como lease y marca `SENT` o reprograma con *backoff* hasta `FAILED`.
- `payload` es JSONB y contiene el hecho crudo de autenticación; la adaptación al contrato de EDA ocurre al publicar.

#### `PANEL_AUDIT`

- Registra el actor estable, username, módulo afectado, acción, destino, detalle y momento.
- `actor_sub` referencia lógicamente a una persona LDAP, pero no tiene clave foránea física.
- El actor podría cambiar de username o dejar de existir sin invalidar el registro histórico.
- La persistencia es de mejor esfuerzo: un error se registra en logs y no garantiza la reversión de la mutación LDAP.

#### `PASSWORD_RESET_TOKEN`

- Persiste únicamente el hash SHA-256 del token de recuperación.
- `sub` y `uid` identifican lógicamente a la cuenta LDAP.
- `used_at` nulo indica un token todavía no consumido; `expires_at` controla su vigencia.
- Al emitir un nuevo token se eliminan los tokens anteriores de la misma persona dentro de la misma transacción.
- El consumo utiliza una actualización condicional para impedir el doble canje concurrente.

#### `PASSWORD_RESET_REQUEST`

- Registra solicitudes aceptadas por el limitador para aplicar enfriamiento y topes por cuenta e IP.
- `uid` está normalizado, pero puede no corresponder a una persona existente; por eso no se dibuja una relación obligatoria con LDAP.
- Las solicitudes rechazadas por el limitador no generan una fila y reciben igualmente `204` hacia el cliente.

#### `REVOKED_ACCESS_TOKEN`

- Denylist de `jti` para invalidar inmediatamente el access token opcional presentado en logout.
- La fila vive hasta `expires_at`; una tarea programada elimina entradas que ya no pueden validar por vencimiento natural.

#### `REVOKED_SUBJECT_CUTOFF`

- Mantiene un único corte temporal por `sub`. El `RevokedJwtValidator` de este backend rechaza todo access token con `iat <= revoked_before`.
- Se avanza al cambiar o recuperar la contraseña, complementando la revocación de refresh tokens. La deshabilitación administrativa todavía no invoca este servicio.
- `expires_at` acota la vida de la fila al máximo vencimiento de los access tokens afectados.

### Configuración

#### `CLIENT_CONFIG`

- No es una tabla: representa los clientes cargados desde configuración.
- Los clientes `human` utilizan `audience` y un `module`, excepto el cliente administrativo `transversal`.
- Los clientes `service` utilizan `client_secret`, audiencia `citypass` y un `namespace`; no representan personas ni contienen grupos.
- `client_id` vincula lógicamente un refresh token con el cliente humano que inició la sesión.

## 4. Cardinalidades y límites

| Relación | Cardinalidad | Interpretación |
| --- | --- | --- |
| Módulo–persona | 1 a 0..N | Cada persona pertenece a un módulo y cada módulo puede contener muchas personas. |
| Módulo–grupo | 1 a 0..N | Cada grupo pertenece a un módulo y cada módulo puede contener muchos grupos. |
| Persona–grupo | 0..N a 0..N | Una persona puede integrar varios grupos y un grupo puede contener varias personas. |
| Persona–refresh token | 1 a 0..N | Una persona puede tener varias sesiones y varios eslabones históricos por cadena. |
| Persona–token de recuperación | 1 a 0..1 | El servicio conserva como máximo una fila de recuperación por `sub`. |
| Persona–auditoría | 1 a 0..N | Una persona con rol administrativo puede producir múltiples registros históricos. |
| Persona–anomalía bloqueada | 1 a 0..N | Una persona puede acumular bloqueos y aprobaciones consumibles; la relación por `user_sub` es lógica. |
| Persona–access token revocado | 1 a 0..N | Un sujeto puede tener varios `jti` denylistados hasta su expiración. |
| Persona–corte de revocación | 1 a 0..1 | Existe como máximo un corte vigente por `sub`. |
| Cliente–refresh token | 1 a 0..N | Cada refresh token fue emitido para un cliente humano registrado. |
| Cliente–anomalía bloqueada | 1 a 0..N | El evento conserva el `client_id` humano para que una aprobación no se reutilice en otro cliente. |

## 5. Relaciones deliberadamente no dibujadas

- `LOGIN_ATTEMPT.username` no es clave foránea porque deben registrarse intentos contra usuarios inexistentes.
- `PASSWORD_RESET_REQUEST.uid` no es clave foránea porque el endpoint aplica medidas anti-enumeración también a cuentas inexistentes.
- `LOGIN_LOCKOUT.username` no es clave foránea porque también se limitan intentos contra usernames inexistentes.
- `PANEL_AUDIT.target` puede representar un DN, username, grupo u otro identificador; no referencia una única entidad.
- `EVENT_OUTBOX.payload` conserva el evento a entregar, pero no crea una clave foránea hacia LDAP: el sujeto puede cambiar o dejar de existir antes del reintento.
- Los JWT completos no se almacenan. Solo se conserva el `jti` revocado o el corte temporal del sujeto; la firma y el resto de claims se validan mediante JWKS.
- Las decisiones `BLOCK` se persisten en `ANOMALY_BLOCKED_EVENT`; la decisión `REVIEW` todavía no tiene una columna en `LOGIN_ATTEMPT`.

## 6. Correcciones respecto del diagrama original

- Se reemplazó `revoked` por `revoked_at`, que es la columna real.
- Se agregó `chain_id`, `client_id`, `audience`, `issued_at` y el identificador estable `sub` al refresh token.
- Se completaron las diez tablas de `schema.sql`, incluidas `LOGIN_LOCKOUT`, `ANOMALY_BLOCKED_EVENT`, `EVENT_OUTBOX`, `REVOKED_ACCESS_TOKEN` y `REVOKED_SUBJECT_CUTOFF`.
- Se corrigió la falsa clave foránea física entre PostgreSQL y LDAP: la relación es lógica.
- Se eliminó el atributo inexistente `module` de `LDAP_GROUP`; el módulo se deriva del DN.
- Se incorporó la entidad conceptual `LDAP_MODULE` y la separación entre `People` y `Groups`.
- Se distinguieron clientes humanos y de servicio dentro de la configuración.
- Se documentó que `memberOf` es operacional y que la membresía primaria vive en `LDAP_GROUP.member`.
