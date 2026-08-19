# Entity-Relationship Diagram — Base de Datos

```mermaid
erDiagram
    REFRESH_TOKENS {
        UUID id PK
        VARCHAR username
        VARCHAR full_name
        VARCHAR email
        VARCHAR token_hash UK "SHA-256, 64 chars"
        VARCHAR roles "CSV: ciudadano,admin"
        TIMESTAMP issued_at
        TIMESTAMP expires_at
        BOOLEAN revoked "default: false"
    }

    LOGIN_ATTEMPTS {
        UUID id PK
        VARCHAR username
        VARCHAR ip_address "IPv4/IPv6, max 45 chars"
        VARCHAR user_agent
        BOOLEAN successful
        TIMESTAMP attempted_at
    }

    REFRESH_TOKENS ||--o{ LOGIN_ATTEMPTS : "mismo usuario"
```

## Índices

| Tabla | Índice | Campo(s) | Propósito |
|-------|--------|----------|-----------|
| `refresh_tokens` | `idx_refresh_tokens_username` | `username` | Buscar tokens por usuario (logout, rotación) |
| `login_attempts` | `idx_login_attempts_username` | `username` | Contar intentos fallidos por usuario |
| `login_attempts` | `idx_login_attempts_attempted_at` | `attempted_at` | Ventana deslizante de lockout |

## Política de retención

- **refresh_tokens**: Los tokens revocados/expirados se pueden limpiar periódicamente (cleanup job)
- **login_attempts**: Los registros viejos se "auto-limpian" de la ventana deslizante (15 min). Se pueden purgar registros con `attempted_at` > 24h para mantener la tabla liviana
