# Architecture Decision Records (ADR) — Login Federado

Este directorio contiene las decisiones de arquitectura documentadas del módulo Login Federado.

## Lista de ADRs

| ADR | Título | Estado |
|-----|--------|--------|
| [ADR-001](ADR-001-ldap-fuente-identidades.md) | LDAP como fuente de identidades | Aceptado |
| [ADR-002](ADR-002-jwt-rs256.md) | JWT RS256 como mecanismo de tokens de acceso | Aceptado |
| [ADR-003](ADR-003-refresh-tokens-opacos-rotacion.md) | Refresh tokens opacos con rotación y hash SHA-256 | Aceptado |
| [ADR-004](ADR-004-bloqueo-fuerza-bruta-ventana-deslizante.md) | Bloqueo por fuerza bruta — ventana deslizante | Aceptado |
| [ADR-005](ADR-005-stateless-sin-sesiones.md) | API stateless sin sesiones de servidor | Aceptado |
| [ADR-006](ADR-006-event-bus-placeholder.md) | Event bus — placeholder con abstracción | Aceptado |
| [ADR-007](ADR-007-dockerfile-multi-stage.md) | Dockerfile multi-stage para la aplicación | Aceptado |

## Formato

Cada ADR sigue la plantilla estándar:
- **Estado**: Propuesto / Aceptado / Deprecado / Reemplazado
- **Contexto**: Problema que se resuelve
- **Opciones consideradas**: Pros y contras de cada alternativa
- **Decisión**: Qué se eligió y por qué
- **Consecuencias**: Impacto de la decisión
