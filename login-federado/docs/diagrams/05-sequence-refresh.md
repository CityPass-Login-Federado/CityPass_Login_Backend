# Secuencia — Rotación del refresh token

**Fecha de revisión:** 24 de septiembre de 2026
**Endpoint:** `POST /auth/refresh`

Cada uso válido rota el refresh token. La reutilización, incluida una carrera concurrente, invalida la cadena completa de la sesión.

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant C as Cliente del módulo
    participant AC as AuthController
    participant AS as AuthService
    participant RT as RefreshTokenService
    participant DB as PostgreSQL
    participant LDAP as LDAP corporativo
    participant CR as ClientRegistry
    participant JWT as AccessTokenIssuer
    participant EP as EventPublisher

    U->>C: Solicita renovar la sesión
    C->>AC: POST /auth/refresh<br/>{refreshToken}
    AC->>AS: refresh(refreshToken)
    AS->>RT: Buscar por hash SHA-256
    RT->>DB: Consultar token
    alt Token inexistente o vencido
        RT-->>AS: Refresh token inválido
        AS-->>AC: 401 Unauthorized
        AC-->>C: 401 Unauthorized
    else Token ya revocado
        RT->>DB: Revocar la cadena completa
        RT-->>AS: Reutilización detectada
        AS-->>AC: 401 Unauthorized
        AC-->>C: 401 Unauthorized
    else Token vigente
        RT->>DB: Revocación condicional atómica
        alt Otro proceso lo rotó primero
            RT->>DB: Revocar la cadena completa
            RT-->>AS: Reutilización concurrente detectada
            AS-->>AC: 401 Unauthorized
            AC-->>C: 401 Unauthorized
        else Revocación confirmada
            AS->>LDAP: Recargar identidad por subject
            LDAP-->>AS: Identidad y grupos actuales
            AS->>CR: Revalidar cliente, audiencia y módulo
            CR-->>AS: Configuración vigente
            alt Identidad o acceso al módulo dejó de ser válido
                AS-->>AC: 401 Unauthorized
                AC-->>C: 401 Unauthorized
            else Acceso todavía válido
                AS->>JWT: Emitir nuevo access token RS256, 15 min
                AS->>RT: Crear sucesor en la misma cadena
                RT->>DB: Guardar nuevo hash y vencimiento de 8 h
                AS->>EP: Publicar identidad.refresh
                AS-->>AC: Nuevo access token y refresh token
                AC-->>C: 200 OK
            end
        end
    end
```

## Garantías

- La rotación condicional evita que dos solicitudes válidas consuman el mismo token simultáneamente.
- La detección de reutilización revoca la cadena de refresh tokens, no sólo el token presentado.
- Antes de renovar, se consulta nuevamente LDAP y se comprueba que la audiencia y el módulo continúen habilitados.
- Un access token emitido previamente no se revoca en línea; conserva validez hasta su expiración de quince minutos.
