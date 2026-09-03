# Sequence Diagram — Administración del Panel

```mermaid
sequenceDiagram
    autonumber
    participant D as Delegado (Panel)
    participant SEC as SecurityFilterChain
    participant PC as PanelController
    participant PA as PanelAuthorization
    participant PDS as PanelDirectoryService
    participant LDAP as OpenLDAP
    participant RTS as RefreshTokenService
    participant AUD as PanelAuditService
    participant DB as PostgreSQL

    D->>SEC: Request /panel/** + Authorization: Bearer <JWT>

    rect rgb(240, 240, 255)
        Note over SEC,PA: Autenticación y autorización sin sesión de servidor
        SEC->>SEC: Valida firma RS256 y expiración del JWT
        SEC->>PC: Request autenticado
        PC->>PA: requireDelegate(jwt)
        PA->>PA: Verifica audience = citypass-admin-api
        PA->>PA: Verifica token_use = human y ver = 1
        PA->>PA: Verifica grupo delegados y claim module
    end

    alt JWT ausente, inválido o expirado
        SEC-->>D: 401 Unauthorized
    else JWT sin permisos del panel o sin scope
        PA-->>D: 403 Forbidden
    else Request autorizado
        alt Consultas de personas
            D->>PC: GET /panel/people[/{uid}]
            PC->>PDS: listPeople(module) / findPerson(module, uid)
            PDS->>LDAP: Search personas dentro de ou=People,module
            LDAP-->>PDS: Datos de personas
            PDS-->>PC: PersonView o lista de personas
            PC-->>D: 200 OK
        else Alta, modificación o contraseña
            D->>PC: POST /personas | PUT /personas/{uid} | POST /reset-password
            PC->>PDS: Mutación con delegate.module()
            PDS->>PDS: Valida datos y reglas del módulo
            PDS->>LDAP: Bind/modify persona
            LDAP-->>PDS: Operación aplicada
            PDS->>AUD: record(actor, action, target, detail)
            AUD->>DB: INSERT panel_audit
            DB-->>AUD: Auditoría persistida
            PDS-->>PC: PersonView o 204 No Content
            PC-->>D: 200/201/204
        else Baja o rehabilitación
            D->>PC: POST /personas/{uid}/disable|enable
            PC->>PDS: disablePerson/enablePerson(delegate, module, uid)
            PDS->>LDAP: Bloquea o desbloquea la identidad
            LDAP-->>PDS: Operación aplicada
            alt Deshabilitar persona
                PC->>RTS: revokeAllForSub(employeeNumber)
                RTS->>DB: UPDATE refresh_tokens: revocados
                DB-->>RTS: Refresh tokens revocados
                PC->>AUD: record(SESSIONS_REVOKED)
                AUD->>DB: INSERT panel_audit
            end
            PDS-->>PC: void
            PC-->>D: 204 No Content
        else Consultas y administración de grupos
            D->>PC: GET /panel/groups
            PC->>PDS: listGroups(module)
            PDS->>LDAP: Search groupOfNames dentro del módulo
            LDAP-->>PDS: Datos de grupos
            PDS-->>PC: Lista de grupos
            PC-->>D: 200 OK

            D->>PC: POST /groups | DELETE /groups/{name}
            PC->>PDS: createGroup/deleteGroup(delegate, module, name)
            PDS->>PDS: Valida nombre y grupo reservado delegados
            PDS->>LDAP: Crea o elimina grupo
            LDAP-->>PDS: Operación aplicada
            PDS->>AUD: record(actor, GROUP_CREATED/GROUP_DELETED, target)
            AUD->>DB: INSERT panel_audit
            PDS-->>PC: GroupView o void
            PC-->>D: 201 Created o 204 No Content

            D->>PC: POST/DELETE /groups/{name}/members[/{uid}]
            PC->>PDS: addMember/removeMember(delegate, module, group, uid)
            PDS->>PDS: Valida persona, pertenencia y máximo de 50 grupos
            PDS->>LDAP: Agrega o quita member del grupo
            LDAP-->>PDS: Operación aplicada
            PDS->>AUD: record(actor, MEMBER_ADDED/MEMBER_REMOVED, target)
            AUD->>DB: INSERT panel_audit
            PDS-->>PC: MembershipChangeResponse
            PC-->>D: 200 OK + advertencias si corresponde
        end
    end
```

## Reglas representadas

1. **JWT obligatorio**: todos los endpoints `/panel/**` requieren un access token válido; el Panel no utiliza sesiones de servidor ni cookies.
2. **Delegado y scope**: el token debe tener `aud = citypass-admin-api`, `token_use = human`, `ver = 1`, el grupo `delegados` y un claim `module`. El módulo nunca se recibe por parámetro: se toma del token.
3. **Personas**: permite listar, consultar, crear, modificar, deshabilitar, rehabilitar y resetear contraseñas dentro del módulo del delegado.
4. **Deshabilitación**: no elimina la identidad; la bloquea en LDAP y revoca de inmediato todos sus refresh tokens.
5. **Grupos**: permite listar, crear, eliminar y administrar miembros. El grupo reservado `delegados` no se puede eliminar y solo admite personas, no grupos.
6. **Reglas de seguridad**: usernames y mails son únicos globalmente; los nombres de grupos usan minúsculas, números y guiones; cada persona puede pertenecer a un máximo de 50 grupos, con aviso desde 30.
7. **Auditoría**: las mutaciones del Panel se registran en `panel_audit` con el delegado, módulo, acción, objetivo y detalle.

## Endpoints incluidos

| Área | Operaciones |
|------|-------------|
| Personas | `GET /panel/people`, `GET /panel/people/{uid}`, `POST /panel/people`, `PUT /panel/people/{uid}` |
| Estado y credenciales | `POST /panel/people/{uid}/disable`, `POST /panel/people/{uid}/enable`, `POST /panel/people/{uid}/reset-password` |
| Grupos | `GET /panel/groups`, `POST /panel/groups`, `DELETE /panel/groups/{name}` |
| Membresías | `POST /panel/groups/{name}/members`, `DELETE /panel/groups/{name}/members/{uid}` |
``