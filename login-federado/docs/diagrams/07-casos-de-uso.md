# Casos de uso

Mermaid no posee una notación UML de casos de uso oficial; se usa `flowchart`, que GitHub renderiza nativamente. El color distingue comportamiento implementado de contratos solicitados pero ausentes.

```mermaid
flowchart LR
    UF[Usuario final]
    AD[Delegado de módulo]
    EX[Servicios de otros squads]
    ML[Motor de riesgo]

    subgraph IDP[Módulo de identidad]
        LOGIN([Login federado])
        REFRESH([Rotar refresh token])
        LOGOUT([Logout / revocar refresh])
        JWKS([Obtener JWKS])
        SVC([Obtener token client_credentials])
        PEOPLE([Gestionar personas LDAP])
        GROUPS([Gestionar grupos y membresía individual])
        LOCK([Bloqueo temporal automático])
        AUDIT([Registrar mutaciones del panel])
        EVENT([Registrar usuario.autenticado en log])
        SCORE([Evaluar riesgo])
        ME([Consultar perfil /me — ausente])
        BULK([BulkMembership — ausente])
        UNLOCK([Desbloqueo manual — ausente])
        QUERYAUD([Consultar auditoría — ausente])
        BUS([Publicar/consumir en broker — ausente])
    end

    UF --> LOGIN
    UF --> REFRESH
    UF --> LOGOUT
    LOGIN --> SCORE
    LOGIN --> LOCK
    LOGIN --> EVENT
    ML --> SCORE
    AD --> PEOPLE
    AD --> GROUPS
    PEOPLE --> AUDIT
    GROUPS --> AUDIT
    EX --> JWKS
    EX --> SVC
    UF -.-> ME
    AD -.-> BULK
    AD -.-> UNLOCK
    AD -.-> QUERYAUD
    EX -.-> BUS

    classDef absent fill:#fff0f0,stroke:#b91c1c,stroke-dasharray:5 5;
    class ME,BULK,UNLOCK,QUERYAUD,BUS absent;
```

## Matriz de casos y evidencia

| Actor | Caso | Estado | Evidencia |
|---|---|---|---|
| Usuario | Login federado | Implementado | `POST /auth/login`, `AuthService`, bind LDAP |
| Usuario | Rotación de refresh | Implementado | `POST /auth/refresh`, cadena y detección de reuso |
| Usuario | Logout | Implementado | `POST /auth/logout`; revoca un refresh, no el access token vigente |
| Usuario | Consulta `/me` | Ausente | no existe controller ni mapping |
| Delegado | CRUD de personas | Implementado | `/panel/personas/**`; alta, edición, enable/disable y reset administrativo |
| Delegado | CRUD de grupos | Implementado | `/panel/grupos/**` y membresía individual |
| Delegado | Membresía masiva | Ausente | el frontend llama `/panel/group-memberships/bulk`, sin mapping backend |
| Delegado | Desbloqueo de fuerza bruta | Ausente como API | el lockout expira al salir los intentos de la ventana de 15 minutos |
| Delegado | Consultar auditoría | Ausente | se persiste `panel_audit`, pero no hay endpoint de lectura |
| Otros squads | Validar JWT | Implementado | JWT RS256 + JWKS; la validación ocurre en cada consumidor |
| Otros squads | Token de servicio | Implementado | `POST /oauth/token`, form-urlencoded + Basic |
| Motor ML | Evaluar login | Parcial | `/score`; sin modelo versionado se usan reglas |
| Plataforma EDA | Publicar/consumir eventos | Parcial/Ausente | solo `usuario.autenticado` a logger; no hay consumidor |

## Reglas de autorización del panel

El backend requiere un JWT válido con `aud` que contenga `citypass-admin-api`, `token_use=human`, `ver=1`, claim `module` y grupo `delegados`. El módulo operado siempre proviene del token; el backend no acepta un parámetro `module`.
