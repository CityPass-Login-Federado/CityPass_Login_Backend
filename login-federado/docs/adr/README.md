# Architecture Decision Records (ADR) — Login Federado

Este directorio contiene las decisiones de arquitectura documentadas del módulo Login Federado.

## Lista de ADRs

| ADR | Título |
|-----|--------|
| [ADR-001](ADR-001-ldap-fuente-identidades.md) | LDAP como fuente de identidades |
| [ADR-002](ADR-002-jwt-rs256.md) | JWT RS256 como mecanismo de tokens de acceso |
| [ADR-003](ADR-003-refresh-tokens-opacos-rotacion.md) | Refresh tokens opacos con rotación y hash SHA-256 |
| [ADR-004](ADR-004-bloqueo-fuerza-bruta-ventana-deslizante.md) | Bloqueo por fuerza bruta — ventana deslizante |
| [ADR-005](ADR-005-stateless-sin-sesiones.md) | API stateless sin sesiones de servidor |
| [ADR-006](ADR-006-event-bus-placeholder.md) | Event bus — placeholder con abstracción |
| [ADR-007](ADR-007-dockerfile-multi-stage.md) | Dockerfile multi-stage para la aplicación |
| [ADR-008](ADR-008-python-servicio-deteccion.md) | Python para el servicio de detección de anomalías |
| [ADR-009](ADR-009-ia-ml-deteccion-anomalias.md) | Detección híbrida con Isolation Forest y reglas deterministas |

## Formato

Cada ADR sigue la estructura requerida:

1. **Título**
2. **Quiénes**: participantes de la decisión (nombre y rol)
3. **Consideraciones** (extensible, incluye opciones): contexto, restricciones, supuestos y alternativas evaluadas con pros/contras
4. **Por todo esto, definimos**: la decisión adoptada y su justificación
5. **Consecuencias**: divididas en **positivas** y **negativas**
6. **Referencias (benchmark)**: estándares, guías y fuentes consultadas
