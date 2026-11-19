# Trazabilidad — TASK-171

**Título:** Actualización de Documentación Técnica y Diagramas UML Finales
**Fecha de corte:** 19 de noviembre de 2026
**Alcance excluido:** documentación de Sprints, retrospectivas y sprint reviews.

## Matriz de cumplimiento

| Requisito | Evidencia principal | Estado |
|---|---|---|
| Arquitectura del sistema | C4 niveles 1–3, vistas 4+1 y arquitectura del frontend | Cubierto |
| Diagramas de interacción | Secuencias de login, refresh, logout, panel, OAuth, JWKS y Outbox | Cubierto |
| Casos de uso UML | `15-uml-casos-uso.md`, con `<<include>>` y `<<extend>>` | Cubierto |
| Modelo entidad-relación | `16-uml-entidad-relacion.md`, diez tablas, LDAP y clientes | Cubierto |
| Despliegue local y productivo | `12-cloud-deployment-dev.md`, `13-cloud-deployment-prod.md` y runbooks | Cubierto |
| Decisiones arquitectónicas | ADR-001 a ADR-010 y ADR-F001 a ADR-F004 | Cubierto |
| Documentación completa del servicio | README, índice, API, EDA, testing, integración y demo | Cubierto documentalmente |
| Colaboración inter-squad | `integracion.md`, configuración por módulo y tareas asociadas | Cubierto |
| Caso de uso transversal | Casos TASK-132 a TASK-137 y cierre TASK-170 | Cubierto |
| Demo con datos simulados y eventos reales | `demo-hito-2.md` | Cubierto como procedimiento reproducible |
| Estrategia y evidencia de testing | `testing-evidence.md`, guía manual, tests y workflows | Cubierto con limitaciones declaradas |
| UX, responsive y accesibilidad | README y arquitectura del frontend, Vitest/RTL/jest-axe | Cubierto |
| IA/ML | ADR-008, ADR-009 y documentación del microservicio | Cubierto; PoC sintética declarada |

## Inventario de diagramas finales

| Grupo | Archivos | Propósito |
|---|---|---|
| C4 | 01, 02 y 03 | Contexto, contenedores y componentes |
| Interacción | 04 a 10 | Flujos críticos y publicación de eventos |
| Arquitectura ampliada | 11 a 13 | 4+1 y despliegues dev/prod |
| Requisitos y UML | 14 a 16 | Historias, casos de uso y ER |

Todos se indexan en [diagrams/README.md](diagrams/README.md) y declaran fecha de revisión, alcance y observaciones verificables.

## Limitaciones técnicas visibles

La documentación no oculta los puntos que requieren resolución de código u operación:

- Maven no configura Failsafe; los `*IT.java` no forman parte de la ejecución estándar.
- El workflow actual ejecuta `mvn test jacoco:report`; el check de JaCoCo ligado a `verify` no bloquea ese job.
- Los escáneres configurados con salida no bloqueante informan hallazgos, pero no detienen el pipeline.
- La infraestructura está expresada con Docker Compose; no hay Terraform ni Bicep en este repositorio.
- El Outbox requiere monitorear `PENDING` y `FAILED`, y no define purga automática de filas `SENT`.
- El dataset ML de referencia es sintético; la documentación separa esa métrica del reentrenamiento con datos reales.
