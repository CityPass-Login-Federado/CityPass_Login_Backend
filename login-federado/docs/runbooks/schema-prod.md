# Runbook — Schema y claves JWT en producción

> Por qué existe este archivo: en dev, la app crea todo sola al arrancar
> (claves autogeneradas + `schema.sql` con `DROP TABLE`). En prod, esas dos
> comodidades **destruyen sesiones, auditoría y todos los tokens en cada
> redeploy**. Acá está lo que hay que hacer una vez, y lo que hay que hacer
> cuando el schema cambie.

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

## 2. Schema de Supabase (una vez, MANUAL)

`SPRING_SQL_INIT_MODE=never` en prod: la app **ya no toca el schema**.
La primera vez (o en una base vacía), crearlo a mano con `psql` contra el
pooler de Supabase usando `login-federado/src/main/resources/schema.sql`.

> OJO: `schema.sql` contiene `DROP TABLE`. Solo correrlo tal cual contra una
> base **vacía**. Jamás contra una base con datos.

## 3. Cuando el schema cambie (cada vez)

1. NO agregar `CREATE TABLE` sueltos a `schema.sql` esperando que prod los tome:
   con `mode: never`, prod **ignora** ese archivo.
2. Escribir la migración como SQL versionado (camino a Flyway; mientras tanto,
   archivo `login-federado/docs/runbooks/migrations/V_fecha_que.sql`).
3. Aplicarla a mano contra Supabase con `psql`.
4. `ddl-auto: validate` actúa de red: si el código y la base divergen, la app
   no arranca en vez de corromper datos. Un fallo de arranque post-deploy
   con error de validación de Hibernate = migración pendiente, no bug.

## 4. Verificación post-deploy (2 min, siempre)

1. `kid` estable entre deploys: pedir `/.well-known/jwks.json` y comparar el
   valor de `kid` antes y después.
2. Auditoría intacta: `SELECT COUNT(*), MIN(occurred_at) FROM panel_audit;`
   no debe vaciarse con el deploy.
3. Un refresh emitido ANTES del deploy sigue canjeando 200.

Si 1 falla: se perdió el volumen de keys (ver punto 1).
Si 2 se vació: alguien corrió con `mode: always` o un `down -v` + bootstrap.
Si 3 falla con 1 y 2 sanos: revisar revocaciones, no infraestructura.
