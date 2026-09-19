# Modelo de datos - LDAP, PostgreSQL y configuración

Versión corregida del diagrama ER publicado en Notion. Representa los datos que el backend realmente utiliza y distingue los tres orígenes: directorio LDAP, PostgreSQL y configuración `application.yml`.

Fuente original: [Diagramas UML (CU, ER) - Módulo 2](https://app.notion.com/p/drff/Diagramas-UML-CU-ER-Modulo-2-3c977c7e30048071b13dd90ecd96a45b?source=copy_link)

```mermaid
erDiagram
    LDAP_MODULE ||--o{ LDAP_PERSON : contains
    LDAP_MODULE ||--o{ LDAP_GROUP : contains
    LDAP_PERSON }o--o{ LDAP_GROUP : member
    LDAP_PERSON ||--o{ REFRESH_TOKEN : subject
    LDAP_PERSON ||--o{ PANEL_AUDIT : actor
    CLIENT_CONFIG ||--o{ REFRESH_TOKEN : issued_for

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

## Interpretación técnica

- `LDAP_MODULE`, `LDAP_PERSON` y `LDAP_GROUP` representan entradas del directorio, no tablas SQL.
- El módulo de una persona o grupo se deriva de su DN y de las OUs `People` y `Groups`; no existe un atributo LDAP `module` en los grupos.
- La membresía se guarda en `LDAP_GROUP.member`. `memberOf` en la persona es un atributo operacional mantenido por el overlay de OpenLDAP.
- `employeeNumber` tiene formato `U` seguido de seis dígitos y es el `sub` estable de los tokens humanos.
- `REFRESH_TOKEN.sub` referencia lógicamente a `LDAP_PERSON.employeeNumber`; no hay una clave foránea física entre LDAP y PostgreSQL.
- La revocación se representa mediante `revoked_at`, no mediante un booleano `revoked`.
- `LOGIN_ATTEMPT.username` puede corresponder a un usuario inexistente, porque también registra intentos fallidos; por eso no se dibuja una relación obligatoria con LDAP.
- `PANEL_AUDIT.actor_sub` conserva la identidad estable del actor, pero tampoco posee una clave foránea física contra LDAP.
- `CLIENT_CONFIG` no es una tabla: representa el registro de clientes cargado desde `application.yml`. Incluye clientes humanos y de servicio; `client_secret` y `namespace` solo aplican a servicios, mientras que `module` o `transversal` aplican a clientes humanos.
- Las tablas PostgreSQL reales son `refresh_tokens`, `login_attempts` y `panel_audit`.
