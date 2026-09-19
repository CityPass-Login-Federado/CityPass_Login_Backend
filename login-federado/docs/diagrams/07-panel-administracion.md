# Sequence Diagram — Administración del Panel

```mermaid
sequenceDiagram
    autonumber
    participant D as Delegado (Panel)
    participant SEC as SecurityFilterChain
    participant PC as PanelController
    participant PA as PanelAuthorization
    participant PPS as PanelPersonService
    participant PGS as PanelGroupService
    participant PAS as PanelAccountService
    participant PLS as PanelLdapSupport
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
            PC->>PPS: listPeople(module) / findPerson(module, uid)
            PPS->>LDAP: Search personas dentro de ou=People,module
            LDAP-->>PPS: Datos de personas
            PPS-->>PC: PersonView o lista de personas
            PC-->>D: 200 OK
        else Alta o modificación (con renombre y reparación)
            D->>PC: POST /personas | PUT /personas/{uid}
            PC->>PPS: Mutación con delegate.module()
            PPS->>PPS: Valida datos y reglas del módulo
            PPS->>LDAP: Bind/modify/rename persona
            LDAP-->>PPS: Operación aplicada
            alt Renombre de username
                PPS->>PLS: repairMemberships(module, oldUid, newUid)
                PLS->>LDAP: Reescribe member en los grupos afectados
                LDAP-->>PLS: Membresías reparadas
            end
            PPS->>AUD: record(actor, action, target, detail)
            AUD->>DB: INSERT panel_audit
            DB-->>AUD: Auditoría persistida
            PPS-->>PC: PersonView o 204 No Content
            PC-->>D: 200/201/204
        else Baja, rehabilitación o reset delegado
            D->>PC: POST /personas/{uid}/disable|enable|reset-password
            PC->>PAS: disablePerson/enablePerson/resetPassword(delegate, module, uid)
            PAS->>LDAP: Bloquea, desbloquea o fija contraseña
            LDAP-->>PAS: Operación aplicada
            alt Deshabilitar persona
                PC->>RTS: revokeAllForSub(employeeNumber)
                RTS->>DB: UPDATE refresh_tokens: revocados
                DB-->>RTS: Refresh tokens revocados
                PC->>AUD: record(SESSIONS_REVOKED)
                AUD->>DB: INSERT panel_audit
            end
            PAS-->>PC: void
            PC-->>D: 204 No Content
        else Consultas y administración de grupos
            D->>PC: GET /panel/groups
            PC->>PGS: listGroups(module)
            PGS->>LDAP: Search groupOfNames dentro del módulo
            LDAP-->>PGS: Datos de grupos
            PGS-->>PC: Lista de grupos
            PC-->>D: 200 OK

            D->>PC: POST /groups | DELETE /groups/{name}
            PC->>PGS: createGroup/deleteGroup(delegate, module, name)
            PGS->>PGS: Valida nombre y grupo reservado delegados
            PGS->>LDAP: Crea o elimina grupo
            LDAP-->>PGS: Operación aplicada
            PGS->>AUD: record(actor, GROUP_CREATED/GROUP_DELETED, target)
            AUD-->>DB: INSERT panel_audit
            PGS-->>PC: GroupView o void
            PC-->>D: 201 Created o 204 No Content

            D->>PC: POST/DELETE /groups/{name}/members[/{uid}]
            PC->>PGS: addMember/removeMember(delegate, module, group, uid)
            PGS->>PLS: personExists(module, uid) + membershipCount(uid)
            PLS->>LDAP: Lecturas de existencia y memberOf
            LDAP-->>PLS: Resultados
            PGS->>PGS: Valida pertenencia y máximo de 50 grupos
            PGS->>LDAP: Agrega o quita member del grupo
            LDAP-->>PGS: Operación aplicada
            PGS->>AUD: record(actor, MEMBER_ADDED/MEMBER_REMOVED, target)
            AUD->>DB: INSERT panel_audit
            PGS-->>PC: MembershipChangeResponse
            PC-->>D: 200 OK + advertencias si corresponde
        end
    Note over PPS,PGS: PanelLdapSupport (package-private) concentra la mecánica LDAP con la cuenta panel-writer: DNs, asserts de módulo, lecturas y mapeos. Las reglas y la auditoría viven en cada servicio.
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