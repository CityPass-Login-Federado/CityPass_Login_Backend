# Secuencia — Cierre de sesión

**Fecha de revisión:** 24 de septiembre de 2026
**Endpoint:** `POST /auth/logout`

El cierre de sesión recibe el refresh token en el cuerpo, no requiere un access token y es idempotente.

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant C as Cliente del módulo
    participant AC as AuthController
    participant AS as AuthService
    participant RT as RefreshTokenService
    participant DB as PostgreSQL
    participant EP as EventPublisher

    U->>C: Cierra la sesión
    C->>AC: POST /auth/logout<br/>{refreshToken}
    AC->>AS: logout(refreshToken)
    alt Token vacío o desconocido
        AS-->>AC: Sin cambios
        AC-->>C: 204 No Content
    else Refresh token conocido
        AS->>RT: revokeSingle(refreshToken)
        RT->>DB: Marcar solamente ese token como revocado
        AS->>EP: Publicar identidad.logout
        AS-->>AC: Operación completada
        AC-->>C: 204 No Content
    end
```

## Alcance de la revocación

- El endpoint revoca únicamente el refresh token presentado y no toda la cadena.
- Repetir la solicitud o enviar un token desconocido mantiene la respuesta `204 No Content`.
- El access token existente permanece válido hasta expirar; los servicios consumidores deben respetar su vida máxima de quince minutos.
- La revocación masiva de sesiones pertenece a otros casos: deshabilitación de una persona, cambio de contraseña o restablecimiento de contraseña.
