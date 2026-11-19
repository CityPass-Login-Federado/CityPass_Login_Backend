# Documentación técnica — Login Federado

**Última revisión integral:** 19 de noviembre de 2026
**Alcance:** TASK-171 — Actualización de Documentación Técnica y Diagramas UML Finales

Este índice reúne la documentación entregable del módulo de Login Federado. Describe el estado implementado que será integrado a `main`; no contiene retrospectivas, sprint reviews ni otros artefactos de Scrum.

## Recorrido recomendado

1. [README del proyecto](../../README.md): propósito, ejecución, seguridad, API y operación.
2. [Trazabilidad de la TASK-171](trazabilidad-task-171.md): correspondencia entre rúbrica y evidencia.
3. [Diagramas](diagrams/README.md): arquitectura C4, 4+1, despliegue, secuencias, casos de uso y ER.
4. [ADRs](adr/README.md): decisiones arquitectónicas vigentes y reemplazadas.
5. [Contrato de API](api-contract.md): superficies REST, autorización y fuentes de verdad.
6. [Integración inter-squad](integracion.md): clientes, CORS, cuentas de servicio y casos transversales.
7. [EDA](eda/README.md): contratos, Outbox, publicación y operación.
8. [Evidencia de testing](testing-evidence.md): estrategia, inventario, comandos y límites conocidos.
9. [Guion de demo de Hito 2](demo-hito-2.md): recorrido unificado y verificaciones.
10. [Integración del dashboard de Analítica](integrations/analytics-dashboard-integration.md): cambios del backend, seguridad, cronología y dependencias externas.
11. [Runbooks](runbooks/): despliegue del tablero de Analítica y esquema productivo.

## Artefactos consumibles

- `../swagger.json`: snapshot OpenAPI para importación sin levantar la aplicación. La fuente de verdad en ejecución es `GET /v3/api-docs`.
- `../citypass-login-federado.postman_collection.json`: batería manual de endpoints y casos negativos.
- `../Guia_Testing_Login_Federado.md`: pruebas manuales end-to-end.
- `eda/*.json`: contratos de `LoginExitoso`, `TokenRenovado` y `SesionCerrada`.
- `../../anomaly-detection/README.md` y `../../anomaly-detection/ML/ML_README.md`: servicio de riesgo y PoC de ML.

## Criterio de vigencia

- Los diagramas representan el estado *as-is*, no una arquitectura aspiracional.
- Las limitaciones conocidas se documentan y no se presentan como capacidades verificadas.
- Si el snapshot OpenAPI difiere de `/v3/api-docs`, prevalece la especificación generada por SpringDoc.
- Los datos de producción y secretos nunca se incluyen en el repositorio; la demo utiliza identificadores y consultas reproducibles sin exponer valores sensibles.
