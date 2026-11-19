# Guion reproducible — Demo Hito 2

**Objetivo:** demostrar el módulo finalizado e integrado con datos simulados y eventos reales, sin exponer secretos ni datos personales.
**Duración sugerida:** 8 a 12 minutos.

## 1. Precondiciones

- Backend, frontend, LDAP, PostgreSQL y anomaly-detection saludables.
- Claves RSA persistentes y `issuer` coherente con los consumidores.
- `EDA_PUBLISHER=http`, URLs del gateway y par `EDA_CLIENT_ID=grupo2`/secret correctos en el ambiente de demo.
- Workflows de despliegue corregidos o `.env` verificado manualmente: al corte, CD asigna un secreto a `EDA_CLIENT_ID` y el workflow manual referencia además `SVC_GROUPO2_SECRET`. No iniciar la demo integrada sin comprobar que el id efectivo sea `grupo2`.
- Backend de Analítica accesible y confiando en el JWKS del IdP de la demo.
- Usuario de demo perteneciente a un módulo y `admin-global` disponibles.
- `GET /health` de anomaly-detection informa modelo `v4`, `loaded=true` y modo `SHADOW` esperado.

No mostrar `.env`, secrets, tokens completos ni datos personales durante la presentación.

## 2. Verificación previa

```bash
curl -i https://IDENTIDAD/health
curl -i https://IDENTIDAD/.well-known/jwks.json
```

En PostgreSQL:

```sql
SELECT status, count(*)
FROM event_outbox
GROUP BY status
ORDER BY status;
```

Registrar el conteo inicial. Si existen filas `FAILED`, no modificarlas durante la exposición: explicar la causa y aplicar el runbook acordado antes de comenzar.

## 3. Recorrido unificado

### Paso A — Login con datos simulados

1. Ingresar desde el frontend con un usuario seed del módulo correspondiente.
2. Mostrar que el frontend mantiene tokens solo en memoria y deriva la navegación del JWT.
3. Abrir una operación protegida y verificar autorización por módulo.

Evidencia esperada: respuesta 200, `aud` correcto, grupos esperados y una fila nueva de login en el Outbox.

### Paso B — Evento real

Consultar la última fila sin mostrar el payload completo:

```sql
SELECT id, event_type, status, attempts, created_at, sent_at
FROM event_outbox
ORDER BY created_at DESC
LIMIT 5;
```

Mostrar la transición `PENDING` → `SENT` y el mismo identificador en la evidencia del Event Gateway. Explicar entrega al menos una vez, id estable, `SKIP LOCKED`, reintentos y deduplicación externa.

### Paso C — Refresh y logout

1. Renovar la sesión.
2. Comprobar rotación del refresh token.
3. Cerrar sesión y verificar que el refresh anterior ya no funciona.

Evidencia esperada: eventos `TokenRenovado` y `SesionCerrada`, ambos entregados por el Outbox.

### Paso D — Administración aislada

Ingresar como delegado, listar personas/grupos y mostrar que el módulo queda fijado por el token. Un intento de operar otro módulo debe resultar en 403 o no exponer el recurso.

### Paso E — Anomalía v4 en Shadow

Ejecutar un escenario controlado y mostrar `risk_score`, `model_version=v4`, hashes, `proposed_decision`, `decision` y `mode=SHADOW`. Si la propuesta es `BLOCK`, explicar y verificar que la decisión efectiva sea `REVIEW`: el objetivo de esta demo es evidenciar observabilidad, no afirmar un bloqueo que producción no aplica.

El flujo de bloqueo y aprobación administrativa solo puede demostrarse con una decisión efectiva `BLOCK`, por ejemplo en un ambiente controlado con `ANOMALY_MODE=ENFORCE`. No cambiar el modo en producción ni alterar usuarios reales durante la presentación.

### Paso F — Analítica

Ingresar como `admin-global`, abrir `/panel/analitica` y explicar que el frontend solicita `/admin/analitica`, mientras el backend reenvía el Bearer al Grupo 8 y entrega HTML dentro de un `iframe` sandboxed.

## 4. Caso transversal

Relacionar el recorrido con uno de los casos documentados en `integracion.md`: el personal se autentica con JWT humano, los servicios publican con `client_credentials` y los módulos validan contra el mismo JWKS. La identidad humana viaja como dato del evento; su token no se reenvía al bus.

## 5. Cierre y evidencia

- Salud de los componentes.
- Filas `SENT` generadas durante la demo.
- Evento observado en EDA con el mismo id.
- Pantalla de Analítica accesible solo para `admin-global`.
- Enlace al run de CI de los SHAs presentados.

## 6. Plan de contingencia

| Falla | Continuidad de la demo |
|---|---|
| EDA no responde | Mostrar fila `PENDING`, attempts y próximo reintento; el login debe seguir disponible por Outbox |
| Analítica no responde | Mostrar mapeo 502/503 y que autenticación/panel continúan operativos |
| anomaly-detection no responde | Mostrar comportamiento fail-closed y el error controlado |
| El modelo propone `BLOCK` pero el login continúa | Es el comportamiento esperado en `SHADOW`: mostrar `proposed_decision=BLOCK` y `decision=REVIEW` |
| Frontend no está disponible | Ejecutar el mismo flujo desde Postman y mostrar contratos HTTP |
| Ambiente productivo no es seguro para cambios | Usar datos seed en el ambiente de demo; no improvisar escrituras en producción |
