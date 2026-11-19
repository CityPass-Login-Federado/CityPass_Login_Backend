# Runbook — Schema y claves JWT en producción

> Por qué existe este archivo: en dev, la app crea todo sola al arrancar.
> En prod, hay dos cosas que nunca deben pasar: perder las claves de firma
> (invalida todos los tokens) y perder tablas (sesiones, intentos, auditoría).

## 1. Claves JWT (una vez, ya resuelto por defecto)

- `docker-compose.prod.yml` monta el volumen `keysdata` en `/app/keys`.
- El **primer** arranque genera el par RSA una sola vez; los redeploys lo
  reutilizan (`kid` estable, tokens válidos entre deploys).
- OJO: `docker compose down -v` **borra el volumen** y recrea el incidente
  (kid nuevo + 401 masivo). No usar `-v` en prod salvo emergencia; si pasa,
  avisar a los 7 grupos que re-logueen y monitorear el `kid` de
  `/.well-known/jwks.json`.
- Mejora futura: provisionar las claves como secrets + `JWT_AUTO_GENERATE_KEYS=false`
  (la app falla al arrancar si faltan, en vez de fabricarlas en silencio).

## 2. Schema: seguro por construcción (sin intervención)

`src/main/resources/schema.sql` es **idempotente sin DROP**: solo
`CREATE TABLE/INDEX IF NOT EXISTS`. Corre en cada arranque
(`sql.init.mode=always`) en dev, CI y prod:

- Base vacía (CI/smoke, primer deploy): crea todo y sigue.
- Base existente (prod): no-op, no toca ni una fila. Sesiones, intentos y
  auditoría sobreviven a todos los redeploys.
- `ddl-auto: validate` actúa de red: si el código y la base divergen
  (ej: alguien cambió una columna solo en la entidad), la app no arranca
  en vez de corromper datos.

## 3. Cuando el schema cambie (cada vez)

`CREATE IF NOT EXISTS` **no migra**: si una tabla existe con otro formato,
se la saltea y `validate` voltea el arranque (a propósito: mejor un deploy
rojo y visible que datos rotos en silencio). Procedimiento:

1. Escribir la migración como SQL versionado en
   `login-federado/docs/runbooks/migrations/V_fecha_que.sql`
   (camino a Flyway; mientras tanto, manual).
2. Aplicarla a mano contra Supabase con `psql` (ver credenciales del deploy).
3. Recién después deployar el código que la necesita.
4. Un fallo de arranque post-deploy con error de validación de Hibernate =
   migración pendiente, no bug.

## 4. Verificación post-deploy (2 min, siempre)

1. `kid` estable entre deploys: pedir `/.well-known/jwks.json` y comparar el
   valor de `kid` antes y después.
2. Auditoría intacta: `SELECT COUNT(*), MIN(occurred_at) FROM panel_audit;`
   no debe vaciarse con el deploy.
3. Un refresh emitido ANTES del deploy sigue canjeando 200.

Si 1 falla: se perdió el volumen de keys (ver punto 1).
Si 2 se vació: revisar que nadie haya corrido un schema con DROP a mano.
Si 3 falla con 1 y 2 sanos: revisar revocaciones, no infraestructura.
