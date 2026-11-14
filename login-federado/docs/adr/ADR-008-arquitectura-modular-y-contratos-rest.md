# ADR-008: Arquitectura modular, errores centralizados y contratos REST

## Estado

Accepted — 14 de noviembre de 2026.

## Contexto y Problema

El backend reúne autenticación, tokens, LDAP, panel, auditoría, riesgo y eventos. Sin límites explícitos, controllers pueden concentrar lógica, el dominio quedar acoplado a infraestructura y los errores filtrar datos sensibles. Los dos repositorios también necesitan un contrato HTTP único.

## Opciones Consideradas (Pros y Contras de cada una)

### Aplicación por controllers con acceso directo a repositorios

- Pros: menos clases y desarrollo inicial rápido.
- Contras: baja testabilidad, reglas duplicadas y exposición accidental de detalles.

### Microservicio por cada capacidad

- Pros: despliegue y escalado independiente.
- Contras: complejidad operativa desproporcionada; transacciones y contratos distribuidos.

### Monolito modular con puertos para integraciones

- Pros: límites de paquete, pruebas unitarias, un despliegue Java y adaptadores reemplazables.
- Contras: la disciplina modular no está reforzada por módulos de build ni ArchUnit.

## Decisión Tomada y Justificación

Mantener `login-federado` como monolito modular por paquetes: `controller`, `service`, `identity`, `token`, `panel`, `repository`, `event`, `security`, `config`, `dto` y `exception`. Controllers validan/transfieren; services aplican reglas; repositories persisten; LDAP, riesgo y eventos son adaptadores.

`GlobalExceptionHandler` normaliza errores. Todas las fallas de login responden 401 genérico para impedir enumeración; el panel usa 400/403/404/409/422/500 según categoría.

## Consecuencias Técnicas

- Los DTO evitan exponer entidades JPA y fijan snake_case solo donde se anotó explícitamente.
- Swagger está disponible en `/docs` y OpenAPI en `/v3/api-docs`.
- La abstracción `EventPublisher` reduce acoplamiento al transporte.
- El contrato no está automatizado entre repositorios: hoy el frontend usa rutas inglesas y el backend españolas, además de operaciones inexistentes. Deben añadirse pruebas de contrato/OpenAPI antes de considerar integrada la UI.
- No existe `/me`; tampoco paginación, bulk, consulta de auditoría ni administración global en el backend.
