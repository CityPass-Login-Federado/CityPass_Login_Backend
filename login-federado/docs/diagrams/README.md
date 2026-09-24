# Diagramas de arquitectura y funcionales

**Última revisión integral:** 24 de septiembre de 2026

Esta carpeta documenta el estado implementado del Módulo de Login Federado e Identidad de CityPass+. El módulo se representa como un sistema funcional aislado; los restantes módulos y el Event Gateway son dependencias externas hasta la fase de integración.

## Índice

| Documento | Alcance |
| --- | --- |
| `01-c4-contexto.md` | C4 nivel 1: personas, sistema de interés y sistemas externos |
| `02-c4-contenedores.md` | C4 nivel 2: API, anomalías, LDAP y PostgreSQL |
| `03-c4-componentes.md` | C4 nivel 3: componentes del contenedor Spring Boot |
| `04-sequence-login.md` | Login humano, bloqueo, LDAP, riesgo y emisión de tokens |
| `05-sequence-refresh.md` | Rotación atómica y detección de reutilización |
| `06-sequence-logout.md` | Logout público e idempotente |
| `07-panel-administracion.md` | Delegados, administración global, operaciones y auditoría |
| `08-sequence-client-credentials.md` | OAuth 2.0 Client Credentials |
| `09-sequence-jwks.md` | Validación descentralizada mediante JWKS |
| `10-sequence-metricas.md` | Eventos crudos para EDA y métricas posteriores |
| `11-vistas-4mas1.md` | Vistas lógica, desarrollo, procesos, física y escenarios |
| `12-cloud-deployment-dev.md` | Despliegue local con Docker Compose |
| `13-cloud-deployment-prod.md` | Despliegue productivo y CI/CD |
| `14-historias-usuario.md` | Historias de usuario |
| `15-uml-casos-uso.md` | UML de casos de uso |
| `16-uml-entidad-relacion.md` | UML entidad-relación |

## Convenciones

- Los documentos C4 mantienen un nivel de abstracción por diagrama: contexto, contenedores y componentes.
- Las flechas están rotuladas con intención y, cuando corresponde, tecnología o protocolo.
- Las secuencias describen comportamiento actual; los riesgos y mejoras pendientes están separados de los flujos implementados.
- Los despliegues distinguen explícitamente desarrollo y producción.

