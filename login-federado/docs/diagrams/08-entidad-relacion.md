# Modelo entidad-relación

El DER se deriva de `login-federado/src/main/resources/schema.sql` y de las tres entidades JPA. El esquema se elimina y recrea en cada arranque porque `spring.sql.init.mode=always`; es una configuración de desarrollo que invalida sesiones activas.

```mermaid
erDiagram
    REFRESH_TOKENS {
        UUID id PK
        VARCHAR_16 sub "employeeNumber LDAP"
        UUID chain_id "sesión lógica"
        VARCHAR_255 client_id
        VARCHAR_255 audience
        VARCHAR_64 token_hash UK "SHA-256 Base64"
        TIMESTAMP issued_at
        TIMESTAMP expires_at
        TIMESTAMP revoked_at NULL
    }

    LOGIN_ATTEMPTS {
        UUID id PK
        VARCHAR_255 username
        VARCHAR_45 ip_address NULL
        VARCHAR_500 user_agent NULL
        BOOLEAN successful
        TIMESTAMP attempted_at
    }

    PANEL_AUDIT {
        UUID id PK
        VARCHAR_16 actor_sub "employeeNumber LDAP"
        VARCHAR_255 actor_uid
        VARCHAR_64 module
        VARCHAR_64 action
        VARCHAR_512 target
        VARCHAR_1024 detail NULL
        TIMESTAMP occurred_at
    }

    LDAP_PERSON {
        DN dn PK
        STRING employeeNumber UK
        STRING uid UK
        STRING mail UK
        STRING userPassword
        STRING pwdAccountLockedTime NULL
    }

    LDAP_GROUP {
        DN dn PK
        STRING cn
        DN member
    }

    LDAP_PERSON ||--o{ REFRESH_TOKENS : "sub lógico; sin FK"
    LDAP_PERSON ||--o{ LOGIN_ATTEMPTS : "uid lógico; sin FK"
    LDAP_PERSON ||--o{ PANEL_AUDIT : "actor_sub lógico; sin FK"
    LDAP_PERSON }o--o{ LDAP_GROUP : "member/memberOf"
```

## Tablas SQL

### `refresh_tokens`

Guarda solo el hash del token opaco. `chain_id` agrupa los eslabones de una sesión; el token usado recibe `revoked_at` y el siguiente conserva la cadena. Los índices son `idx_refresh_tokens_sub`, `idx_refresh_tokens_chain` y el índice único derivado de `token_hash`.

### `login_attempts`

Es el histórico consultado por `LoginAttemptService` para el lockout y por FastAPI para features. No hay tabla `LoginLockout`: el bloqueo se deriva contando fallos de la ventana. Índices: username y timestamp.

### `panel_audit`

Registra mutaciones administrativas con actor, módulo, acción y objetivo. No tiene FK porque las identidades viven en LDAP y se preserva el identificador aunque la ficha cambie. La aplicación solo inserta, pero la base no impone append-only y el repositorio hereda operaciones CRUD.

## Directorio LDAP y relaciones lógicas

- Persona: `inetOrgPerson` bajo `ou=People,ou=<Módulo>`, identificada establemente por `employeeNumber` y autenticada por bind.
- Grupo: `groupOfNames` bajo `ou=Groups,ou=<Módulo>`; `member` contiene DN absolutos y el overlay produce `memberOf`.
- El placeholder de `ou=ServiceAccounts` evita grupos vacíos y se filtra de las vistas.
- `sub`, `actor_sub` y `username` son referencias lógicas, no claves foráneas, porque PostgreSQL no puede declarar FK hacia LDAP.

## Elementos solicitados que no existen

No hay entidad/tabla `RawAuthenticationEvent`, `LoginLockout`, sesión materializada, métricas ni proyecciones. Sus funciones parciales se cubren con `login_attempts` y `refresh_tokens`. Documentarlas como tablas reales sería incorrecto.
