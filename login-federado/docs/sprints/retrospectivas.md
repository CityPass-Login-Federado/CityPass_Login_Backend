# Retrospectivas — Squad 2, Login Federado

Las retrospectivas se hicieron de forma oral en las reuniones del equipo al cierre de cada sprint. Los Sprints 0 a 2 se presentaron en el Hito 1 (story map). Este documento las registra por escrito el 18/11/2026, reconstruidas a partir del tablero de Jira (proyecto TASK) y del historial de Git de `CityPass_Login_Backend` y `CityPass_Login_Frontend`.

Los commits por sprint son de `main` sin merges, backend / frontend.

---

## Sprint 0 — Planificación (06/08 al 27/08)

**Objetivo (Jira):** organizar el equipo, definir roles y entender el proyecto.
**Cierre:** 27/08, en fecha. Commits: 51 / 6.

**Bien**
- Se cerró en fecha con todo lo planificado: squads, módulo, stack, herramientas, gestión de roles hacia los demás módulos y formato del JWT (TASK-1 a TASK-12).
- El backend arrancó temprano: el 16/08 ya había estructura, login simplificado y refresh token.
- El entorno local con Docker Compose (LDAP + PostgreSQL) quedó listo desde el inicio (TASK-12).

**Mal**
- El frontend arrancó con poca actividad (6 commits) mientras el backend avanzaba rápido.

**Cambiamos**
- Para el Sprint 1 se cargaron tareas de frontend por pantalla (TASK-18 a TASK-25), para que ese trabajo se viera en el tablero.

---

## Sprint 1 — Desarrollo Modular I (28/08 al 10/09)

**Objetivo (Jira):** desarrollo del módulo, definición de APIs y diseño inicial del modelo de datos.
**Cierre:** 17/09, una semana después de la fecha prevista. Commits: 21 / 9.

**Bien**
- Quedaron definidos los contratos de autenticación y del panel (TASK-32) y se comunicó el JWT a los demás módulos (TASK-43).
- Se montó la base de DevOps: pipelines, smoke test automático y deploy a cloud (TASK-39 a TASK-41).
- Arrancó el microservicio de detección de anomalías (TASK-30).

**Mal**
- El sprint se cerró una semana tarde.
- Algunas tareas se cargaron al tablero después de terminado el sprint (TASK-47 y TASK-48, creadas el 17/09).

**Cambiamos**
- En el Sprint 2 las tareas se dividieron más chicas y por responsable (bugs, fixes y endpoints por separado).

---

## Sprint 2 — Desarrollo Modular II (11/09 al 23/09)

**Objetivo (Jira):** finalizar el módulo, implementar testing y corregir bugs.
**Cierre:** 24/09, junto con la entrega del Hito 1. Commits: 54 / 23.

**Bien**
- Se entregó el Hito 1 con el módulo funcional sin integración y el release MVP (TASK-50, TASK-77).
- Se completó el flujo seguro de recupero de contraseña: token de un solo uso, anti-enumeración y SMTP real (TASK-80 a TASK-84).
- Se refactorizó el panel en servicios separados (TASK-94 a TASK-100).
- Se agregó lint al CI (TASK-121).

**Mal**
- Muchos bugs aparecieron cerca de la entrega: deshabilitar persona (TASK-62 y TASK-110, con un revert en TASK-106), modificación en LDAP (TASK-59) y un NPE en `changePassword` (TASK-83).
- El Quality Gate de SonarCloud falló en un PR por 0% de cobertura en código nuevo y por `csrf.disable()`.
- Gran parte de las tareas se registró en Jira en bloque el 19/09, no a medida que se hacían.

**Cambiamos**
- SonarCloud y el lint pasaron a correr en cada PR, y los cambios entran por rama `TASK-XXX` con PR.

---

## Sprint 3 — Planificación de casos transversales (02/10 al 15/10)

**Objetivo (Jira):** diseño colaborativo de casos de uso transversales, identificación de servicios por caso y activación de chapters técnicos.
**Cierre:** 23/10, ocho días después de la fecha prevista. Commits: 7 / 0.

**Bien**
- Cada integrante tomó uno de los seis casos de uso transversales y analizó el rol del IdP en cada uno (TASK-132 a TASK-137).
- Se corrigió el modelo del admin global, que pasó al módulo de login (TASK-144).
- Se integraron los primeros clientes de otros squads: CORS de Espacios Públicos y JWKS para EDA.

**Mal**
- Después del Hito 1 bajó mucho el ritmo de desarrollo (7 commits en el backend y ninguno en el frontend).
- Los casos de uso quedaron duplicados en el tablero (TASK-138 a TASK-143).
- El sprint se cerró ocho días tarde.

**Cambiamos**
- Se decidió unificar los Sprints 4 y 5 en una sola iteración de integración, con un objetivo único.

---

## Sprints 4 y 5 — Integración (23/10 al 12/11)

**Objetivo (Jira):** iteración continua de integración transversal (Login Federado + contratos EDA) con el resto de los squads, validando la seguridad de endpoints y la comunicación asincrónica.
**Cierre:** 15/11. Commits: 11 / 9.

**Bien**
- Hubo una tarea de integración por squad: Emergencias y Espacios Públicos, Movilidad, Residuos, Analítica, Reclamos y EDA (TASK-149 a TASK-154).
- La integración con EDA quedó formalizada (TASK-147), con el claim `namespace` en el JWT (TASK-148).
- Se consolidó la publicación de eventos con el patrón Outbox (TASK-159, ADR-010).
- Se agregaron la gestión de bloqueos por anomalía, el desbloqueo de cuentas, la vista de auditoría y el reentrenamiento del modelo (TASK-162, TASK-164, TASK-167, TASK-168).

**Mal**
- Los grupos integraron de formas distintas a pesar de tener guías de integración (token de servicio y humano).
- La integración del dashboard de Analítica dependió de los tiempos de ese squad y no se pudo cerrar en el sprint (TASK-155 sigue en revisión).
- La publicación directa de eventos hacía que una caída de EDA afectara al login.

**Cambiamos**
- Se pasó a Outbox para que el login no dependa de la disponibilidad de EDA.
- Se abrió una auditoría por dimensión de la rúbrica para el cierre (TASK-174 a TASK-184).

---

## Sprint 6 — Cierre del Hito 2 (13/11 al 18/11)

**Objetivo (Jira):** pruebas E2E y smoke tests full-stack, estabilizar el despliegue cloud con datos demo y consolidar la documentación técnica para el Hito 2.
**Commits:** 27 / 11.

**Bien**
- Se auditó el módulo dimensión por dimensión contra la rúbrica.
- Se cerraron la revisión de los casos de uso transversales con otros squads y la presentación (TASK-170, TASK-173).
- Se mergeó el proxy de Analítica en el backend y la pantalla en el frontend.

**Mal**
- Se concentró mucho trabajo en pocos días antes de la entrega: casi tantos commits en 6 días como en los Sprints 3, 4 y 5 juntos.
- El sprint no se inició formalmente en el tablero (quedó en estado futuro).
- Llegaron al cierre con trabajo en curso: E2E (TASK-169), diagramas finales (TASK-171) y verificación del despliegue (TASK-172).

**Cambiamos**
- Se priorizó cerrar y documentar lo que ya funcionaba antes que agregar funcionalidades nuevas.

---

## Retro de cierre del cuatrimestre

**Bien**
- El módulo es el IdP de toda la plataforma y otros squads lo usan en producción.
- Las decisiones técnicas quedaron documentadas en 10 ADRs del backend y 4 del frontend.
- El CI/CD quedó completo: lint, tests, SonarCloud, escaneo de secretos e imágenes, smoke tests y deploy automático.

**Mal**
- El ritmo fue desparejo: picos antes de cada entrega y caída después del Hito 1.
- El tablero se actualizó muchas veces en bloque y no día a día.
- La documentación quedó atrás del código (diagramas y README).

**Para la próxima**
- Mantener el tablero al día durante el sprint, no al cierre.
- Actualizar diagramas y README en el mismo PR que cambia el código.
- Fijar con los squads dependientes un contrato de integración único desde el principio.
