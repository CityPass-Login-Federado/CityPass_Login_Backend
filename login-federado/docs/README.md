# Documentación técnica — Login Federado

**Última revisión integral:** 19 de noviembre de 2026
**Alcance:** TASK-171 — Actualización de Documentación Técnica y Diagramas UML Finales

Este índice reúne la documentación entregable del módulo de Login Federado. Describe el estado implementado que será integrado a `main`; no contiene retrospectivas, sprint reviews ni otros artefactos de Scrum.

## Recorrido recomendado

1. [README del proyecto](../../README.md): propósito, ejecución, seguridad, API y operación.
2. [Trazabilidad de la TASK-171](trazabilidad-task-171.md): correspondencia entre rúbrica y evidencia.
3. [Cierre de la auditoría pre Hito 2](auditoria-pre-hito-2.md): hallazgos, correcciones documentales, pendientes técnicos y evidencia exigida.
4. [Diagramas](diagrams/README.md): arquitectura C4, 4+1, despliegue, secuencias, casos de uso y ER.
5. [ADRs](adr/README.md): decisiones arquitectónicas vigentes y reemplazadas.
6. [Contrato de API](api-contract.md): superficies REST, autorización y fuentes de verdad.
7. [Integración inter-squad](integracion.md): clientes, CORS, cuentas de servicio y casos transversales.
8. [EDA](eda/README.md): contratos, Outbox, publicación y operación.
9. [Evidencia de testing](testing-evidence.md): estrategia, inventario, comandos y límites conocidos.
10. [Guion de demo de Hito 2](demo-hito-2.md): recorrido unificado y verificaciones.
11. [Integración del dashboard de Analítica](integrations/analytics-dashboard-integration.md): cambios del backend, seguridad, cronología y dependencias externas.
12. [Runbooks](runbooks/): despliegue del tablero de Analítica y esquema productivo.

## Artefactos consumibles

- `../swagger.json`: snapshot OpenAPI para importación sin levantar la aplicación. La fuente de verdad en ejecución es `GET /v3/api-docs`.
- `../citypass-login-federado.postman_collection.json`: batería manual de endpoints y casos negativos.
- `../Guia_Testing_Login_Federado.md`: pruebas manuales end-to-end.
- `eda/*.json`: contratos de `LoginExitoso`, `TokenRenovado` y `SesionCerrada`.
- `../../anomaly-detection/README.md` y `../../anomaly-detection/ML/ML_README.md`: runtime de riesgo, modelo v4, política, métricas y modo `SHADOW`.

## Criterio de vigencia

- Los diagramas representan el estado *as-is*, no una arquitectura aspiracional.
- Las limitaciones conocidas se documentan y no se presentan como capacidades verificadas.
- Si el snapshot OpenAPI difiere de `/v3/api-docs`, prevalece la especificación generada por SpringDoc.
- Los datos de producción y secretos nunca se incluyen en el repositorio; la demo utiliza identificadores y consultas reproducibles sin exponer valores sensibles.
- Los hallazgos abiertos de la auditoría —EDA client id, binding de timeouts, Failsafe/JaCoCo, CSRF de Swagger, lockfile y E2E— se mantienen visibles en trazabilidad, testing, EDA y el guion de demo; no se presentan como resueltos por un cambio documental.
