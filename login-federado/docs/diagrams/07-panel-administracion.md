# Secuencia — Administración del directorio desde el panel

**Estado representado:** AS-IS de `/panel/**` para delegados de módulo y administradores globales.

```mermaid
sequenceDiagram
    autonumber
    actor O as Operador
    participant W as Panel web
    participant SEC as SecurityFilterChain
    participant PC as PanelController
    participant PA as PanelAuthorization
    participant PDS as PanelDirectoryService
    participant LDAP as OpenLDAP
    participant RTS as RefreshTokenService
    participant AUD as PanelAuditService
    participant DB as PostgreSQL

    O->>W: Opera el panel
    W->>SEC: Request /panel/** + Bearer JWT
    SEC->>SEC: Valida firma RS256, expiración e issuer

    alt JWT ausente, inválido o expirado
        SEC-->>W: 401 Unauthorized
    else JWT válido
        SEC->>PC: Request autenticado
        PC->>PA: requireDelegate(jwt)
        PA->>PA: Verifica aud=citypass-admin-api, token_use=human y ver=1

        alt groups contiene delegados
            PA->>PA: Exige claim module
            PA-->>PC: Delegate(sub, uid, module, global=false)
            Note over PC: Cualquier ?module= enviado por un delegado normal se ignora
        else groups contiene admin-global
            PA-->>PC: Delegate(sub, uid, global=true)
            PC->>PC: Exige y valida un parámetro module permitido
        else no tiene rol de panel
            PA-->>W: 403 Forbidden
        end
    end

    alt Listar módulos permitidos
        W->>PC: GET /panel/modules
        PC-->>W: 200 con los seis módulos permitidos

    else Consultar personas
        W->>PC: GET /panel/people o /panel/people/{uid} con filtros opcionales
        PC->>PDS: listPeople/findPerson(módulo resuelto, criterios)
        PDS->>LDAP: Search dentro del OU People del módulo
        LDAP-->>PDS: Personas del módulo
        PDS-->>PC: PaginatedResponse o PersonView
        PC-->>W: 200 OK

    else Crear, modificar o resetear contraseña
        W->>PC: POST people, PUT people/{uid} o POST reset-password
        PC->>PDS: Mutación con actor y módulo resueltos
        PDS->>PDS: Valida datos, unicidad global y reglas de módulo
        PDS->>LDAP: add/modify/rename persona
        LDAP-->>PDS: Operación aplicada
        PDS->>AUD: record(actor, action, target, detail)
        AUD->>DB: INSERT panel_audit
        alt falla la persistencia de auditoría
            AUD->>AUD: Registra error en logs y no revierte LDAP
        end
        PC-->>W: 200/201/204

    else Deshabilitar o rehabilitar persona
        W->>PC: POST /panel/people/{uid}/disable|enable
        PC->>PDS: disablePerson/enablePerson(actor, module, uid)
        PDS->>LDAP: Aplica o quita pwdAccountLockedTime
        LDAP-->>PDS: Operación aplicada
        PDS->>AUD: record(PERSON_DISABLED/PERSON_ENABLED)
        opt deshabilitación
            PC->>RTS: revokeAllForSub(employeeNumber)
            RTS->>DB: Revoca todos los refresh tokens de la persona
            PC->>AUD: record(SESSIONS_REVOKED)
        end
        PC-->>W: 204 No Content

    else Consultar o administrar grupos
        W->>PC: Consulta o mutación sobre /panel/groups y sus membresías
        PC->>PDS: Operación con actor y módulo resueltos
        PDS->>PDS: Valida nombre, membresía y máximo de 50 grupos
        Note over PDS: Desde 30 grupos devuelve una advertencia
        Note over PDS: Un delegado no puede borrar ni vaciar el grupo delegados
        Note over PDS: admin-global dispone de la excepción explícita
        PDS->>LDAP: Search/add/modify/delete groupOfNames
        LDAP-->>PDS: Operación aplicada
        PDS->>AUD: record(GROUP_* o MEMBER_*)
        AUD->>DB: INSERT panel_audit
        PC-->>W: 200/201/204
    end
```

## Reglas representadas

1. Todos los endpoints `/panel/**` requieren un access token válido; no se usan sesiones de servidor ni cookies.
2. Todo operador necesita `aud=citypass-admin-api`, `token_use=human` y `ver=1`.
3. Un delegado normal requiere el grupo `delegados` y opera únicamente el `module` de su token.
4. Un administrador global requiere `admin-global` y debe indicar un `?module=` válido en cada operación, salvo `GET /panel/modules`.
5. Deshabilitar una identidad bloquea LDAP y revoca todos sus refresh tokens; los access tokens sobreviven hasta expirar.
6. Personas y grupos se consultan con paginación y filtros dentro del módulo resuelto.
7. Las mutaciones intentan persistir auditoría en `panel_audit`; actualmente una falla de auditoría se registra en logs pero no revierte la modificación LDAP.

## Endpoints incluidos

| Área | Operaciones |
|---|---|
| Módulos | `GET /panel/modules` |
| Personas | `GET /panel/people`, `GET /panel/people/{uid}`, `POST /panel/people`, `PUT /panel/people/{uid}` |
| Estado y credenciales | `POST /panel/people/{uid}/disable`, `POST /panel/people/{uid}/enable`, `POST /panel/people/{uid}/reset-password` |
| Grupos | `GET /panel/groups`, `POST /panel/groups`, `DELETE /panel/groups/{name}` |
| Membresías | `POST /panel/groups/{name}/members`, `DELETE /panel/groups/{name}/members/{uid}` |

Los endpoints operables por un administrador global aceptan `?module=<módulo>`; para delegados normales ese parámetro no altera el módulo del JWT.
