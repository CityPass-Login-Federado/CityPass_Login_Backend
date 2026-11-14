# Architecture Decision Records — Login Federado

Estos son los ADR vigentes. Se actualizaron sobre los archivos originales; solo ADR-008 y ADR-009 son nuevos porque esas decisiones no tenían documento previo.

| ADR | Decisión | Estado |
|---|---|---|
| [ADR-001](ADR-001-ldap-fuente-identidades.md) | OpenLDAP para identidad y PostgreSQL para estado operacional | Accepted |
| [ADR-002](ADR-002-jwt-rs256.md) | JWT RS256 y publicación JWKS | Accepted |
| [ADR-003](ADR-003-refresh-tokens-opacos-rotacion.md) | Refresh opaco, hash, rotación y detección de reuso | Accepted |
| [ADR-004](ADR-004-bloqueo-fuerza-bruta-ventana-deslizante.md) | Lockout por ventana y auditoría administrativa | Accepted |
| [ADR-005](ADR-005-stateless-sin-sesiones.md) | API stateless sin sesiones HTTP | Accepted |
| [ADR-006](ADR-006-event-bus-placeholder.md) | Puerto de eventos y adaptador de logging transitorio | Accepted |
| [ADR-007](ADR-007-dockerfile-multi-stage.md) | Dockerfile multi-stage | Accepted |
| [ADR-008](ADR-008-arquitectura-modular-y-contratos-rest.md) | Arquitectura modular y contratos REST | Accepted |
| [ADR-009](ADR-009-microservicio-riesgo-ml.md) | Microservicio Python de riesgo | Accepted, implementación ML incompleta |

Cada ADR documenta estado, contexto, opciones, decisión, justificación y consecuencias técnicas.
