# Guía de Testing — Login Federado (CityPass+)

Guía práctica para probar de punta a punta: login completo, detección de
anomalías (IA) y métricas diarias. Todos los comandos asumen Windows/CMD
parado en `login-federado/` (donde vive `docker-compose.yml`).

---

## 0. Levantar el entorno

```
cd login-federado
docker compose up --build -d
docker compose ps -a
```

Confirmá que todo esté `Up`/`healthy`. El servicio `ldap-config` es un job
"one-shot": su estado esperado es `Exited (0)`, no `Up`.

Usuarios de prueba reales (contraseña `changeit123` para todos):

| Usuario | Módulo | `clientId` correspondiente | Grupo |
|---|---|---|---|
| `jperez` | Reclamos | `citypass-reclamos-web` | — |
| `mgomez` | Movilidad | `citypass-movilidad-web` | — |
| `delegado-rec` | Reclamos | `citypass-reclamos-web` **o** `citypass-admin-web` (panel) | `delegados` |
| `delegado-mov` | Movilidad | `citypass-movilidad-web` **o** `citypass-admin-web` (panel) | `delegados` |

Clientes de servicio (`client_credentials`):

| `client_id` | secret (dev) | namespace |
|---|---|---|
| `group1` | `group1-secret-dev` | `com.citypass.group1` (EDA) |
| `group5` | `group5-secret-dev` | `com.citypass.analitica` |

---

## 1. Flujo de login completo

### 1.1 JWKS (clave pública)
```
curl http://localhost:8081/.well-known/jwks.json
```

### 1.2 Login humano
El `clientId` tiene que corresponder al módulo real del usuario en LDAP.
```
curl -i -X POST http://localhost:8081/auth/login -H "Content-Type: application/json" -d "{\"username\": \"jperez\", \"password\": \"changeit123\", \"clientId\": \"citypass-reclamos-web\"}"
```
Esperado: `200` con `accessToken` + `refreshToken`. Guardá ambos.

### 1.3 Refresh (rota el token)
```
curl -i -X POST http://localhost:8081/auth/refresh -H "Content-Type: application/json" -d "{\"refreshToken\": \"TU_REFRESH_TOKEN\"}"
```
Esperado: `200` con un `accessToken` y `refreshToken` **nuevos** (el anterior queda invalidado).

### 1.4 Logout
```
curl -i -X POST http://localhost:8081/auth/logout -H "Content-Type: application/json" -d "{\"refreshToken\": \"TU_REFRESH_TOKEN\"}"
```
Esperado: `204 No Content` (siempre, exista o no el token — no filtra información).

### 1.5 Confirmar que el logout revocó de verdad
Repetí el refresh con el mismo token que acabás de revocar:
```
curl -i -X POST http://localhost:8081/auth/refresh -H "Content-Type: application/json" -d "{\"refreshToken\": \"EL_TOKEN_QUE_ACABAS_DE_REVOCAR\"}"
```
Esperado: `401` (si en cambio devuelve un token nuevo, hay un bug de seguridad real).

### 1.6 OAuth2 `client_credentials` (para el bus / otros grupos)
```
curl -i -X POST http://localhost:8081/oauth/token -u "group1:group1-secret-dev" -d "grant_type=client_credentials"
```
Esperado: `200` con `access_token` (JWT de servicio, sin `groups`, con `namespace`).

Casos de error (RFC 6749):
```
curl -i -X POST http://localhost:8081/oauth/token -u "group1:secret-incorrecto" -d "grant_type=client_credentials"
curl -i -X POST http://localhost:8081/oauth/token -u "group1:group1-secret-dev" -d "grant_type=password"
```
Esperado en ambos: `401`.

### 1.7 Panel administrativo (solo delegados)
Login con `citypass-admin-web` (el único cliente `transversal`, habilita el panel):
```
curl -i -X POST http://localhost:8081/auth/login -H "Content-Type: application/json" -d "{\"username\": \"delegado-rec\", \"password\": \"changeit123\", \"clientId\": \"citypass-admin-web\"}"
```

Con el `accessToken` (dura poco — recordá renovarlo si tarda la batería):

```
# Listar personas del módulo
curl -i http://localhost:8081/panel/people -H "Authorization: Bearer TOKEN"

# Crear persona
curl -i -X POST http://localhost:8081/panel/people -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" -d "{\"givenName\": \"Test\", \"sn\": \"Prueba\", \"username\": \"test-qa1\", \"email\": \"test-qa1@citypass.local\", \"temporaryPassword\": \"claveTemp123\"}"

# Crear grupo
curl -i -X POST http://localhost:8081/panel/groups -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" -d "{\"name\": \"grupo-qa-test\"}"

# Agregar miembro
curl -i -X POST http://localhost:8081/panel/groups/grupo-qa-test/members -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" -d "{\"memberUid\": \"test-qa1\"}"

# Sacar miembro
curl -i -X DELETE http://localhost:8081/panel/groups/grupo-qa-test/members/test-qa1 -H "Authorization: Bearer TOKEN"

# Borrar grupo
curl -i -X DELETE http://localhost:8081/panel/groups/grupo-qa-test -H "Authorization: Bearer TOKEN"

# Deshabilitar persona (nunca se borra, solo se bloquea)
curl -i -X POST http://localhost:8081/panel/people/test-qa1/disable -H "Authorization: Bearer TOKEN"

# Rehabilitar
curl -i -X POST http://localhost:8081/panel/people/test-qa1/enable -H "Authorization: Bearer TOKEN"
```

Casos de error:
- Sin token → `401`.
- Token humano que no es delegado → `403`.
- Borrar el grupo reservado `delegados` → debe fallar.

---

## 2. Detección de anomalías (Capa 2 — IA)

Motor de reglas actual (`anomaly-detection/app/rules.py`):

| Señal | Peso |
|---|---|
| IP nunca vista | +0.3 |
| Dispositivo nunca visto | +0.2 |
| ≥3 fallos recientes (15 min) | +0.4 |
| Horario inusual (antes de 5am / después de 23hs) | +0.1 |

Decisión: `≥0.7 → BLOCK` · `≥0.4 → REVIEW` · `<0.4 → ALLOW`.

### 2.1 Probar el motor directamente (sin pasar por Java)

**ALLOW** — historial limpio, mismo IP/device de siempre:
```
docker exec -it citypass-db psql -U citypass -d login_federado -c "INSERT INTO login_attempts (id, username, ip_address, user_agent, successful, attempted_at) VALUES ('11111111-1111-1111-1111-111111111111', 'test-allow', '10.0.0.5', 'AppConocida/1.0', true, now() - interval '2 hours');"

curl -X POST http://localhost:8000/score -H "Content-Type: application/json" -d "{\"username\": \"test-allow\", \"ip\": \"10.0.0.5\", \"user_agent\": \"AppConocida/1.0\", \"timestamp\": \"2026-08-26T22:00:00\", \"success\": true}"
```
Esperado: `risk_score: 0.0`, `decision: "ALLOW"`.

**REVIEW** — usuario nuevo, sin historial:
```
curl -X POST http://localhost:8000/score -H "Content-Type: application/json" -d "{\"username\": \"test-review\", \"ip\": \"10.0.0.6\", \"user_agent\": \"OtraApp/2.0\", \"timestamp\": \"2026-08-26T22:00:00\", \"success\": true}"
```
Esperado: `risk_score: 0.5`, `decision: "REVIEW"`.

**BLOCK** — IP nueva + 3 fallos recientes:
```
docker exec -it citypass-db psql -U citypass -d login_federado -c "
INSERT INTO login_attempts (id, username, ip_address, user_agent, successful, attempted_at) VALUES
('22222222-2222-2222-2222-222222222221', 'test-block', '10.0.0.7', 'AppConocida/1.0', false, now() - interval '10 minutes'),
('22222222-2222-2222-2222-222222222222', 'test-block', '10.0.0.7', 'AppConocida/1.0', false, now() - interval '7 minutes'),
('22222222-2222-2222-2222-222222222223', 'test-block', '10.0.0.7', 'AppConocida/1.0', false, now() - interval '3 minutes');
"

curl -X POST http://localhost:8000/score -H "Content-Type: application/json" -d "{\"username\": \"test-block\", \"ip\": \"10.0.0.99\", \"user_agent\": \"AppConocida/1.0\", \"timestamp\": \"2026-08-26T22:00:00\", \"success\": true}"
```
Esperado: `risk_score: 0.7`, `decision: "BLOCK"`.

### 2.2 Probar end-to-end real (con un usuario LDAP de verdad)

El header `X-Forwarded-For` permite controlar la IP que recibe Java sin
depender de la IP interna de Docker.

```
docker exec -it citypass-db psql -U citypass -d login_federado -c "
INSERT INTO login_attempts (id, username, ip_address, user_agent, successful, attempted_at) VALUES
('33333333-3333-3333-3333-333333333331', 'delegado-rec', '10.0.0.7', 'test-block-device', false, now() - interval '10 minutes'),
('33333333-3333-3333-3333-333333333332', 'delegado-rec', '10.0.0.7', 'test-block-device', false, now() - interval '7 minutes'),
('33333333-3333-3333-3333-333333333333', 'delegado-rec', '10.0.0.7', 'test-block-device', false, now() - interval '3 minutes');
"

curl -i -X POST http://localhost:8081/auth/login -H "Content-Type: application/json" -H "X-Forwarded-For: 10.0.0.99" -A "test-block-device" -d "{\"username\": \"delegado-rec\", \"password\": \"changeit123\", \"clientId\": \"citypass-reclamos-web\"}"
```
Esperado: `401` genérico (misma respuesta que credenciales malas — a propósito,
no filtra el motivo real). La contraseña era correcta, pero la IA lo frenó.

**Confirmar en los logs que fue la IA, no la contraseña:**
```
docker compose logs app --tail=100 | findstr /i "anomal"
```
Debería aparecer una línea `SECURITY : Login rechazado por anomalías: usuario=delegado-rec razones=[...]`.

**Confirmar que quedó registrado como fallo** (retroalimenta la Capa 1):
```
docker exec -it citypass-db psql -U citypass -d login_federado -c "SELECT username, ip_address, successful, attempted_at FROM login_attempts WHERE username = 'delegado-rec' ORDER BY attempted_at DESC LIMIT 5;"
```

### 2.3 Probar el fail-closed (Python caído)
```
docker compose stop anomaly-detection
curl -i -X POST http://localhost:8081/auth/login -H "Content-Type: application/json" -d "{\"username\": \"jperez\", \"password\": \"changeit123\", \"clientId\": \"citypass-reclamos-web\"}"
```
Esperado: `503` (nunca deja pasar el login si no pudo evaluar el riesgo).
```
docker compose start anomaly-detection
```

---

## 3. Métricas diarias (evento al bus)

No hay endpoints HTTP para Analítica — el equipo de Login **publica** un
evento (`identidad.metricas.diarias`) una vez por día vía `EventPublisher`,
igual que `usuario.autenticado`. Hoy `LoggingEventPublisher` solo lo loguea
(placeholder hasta que el Grupo 1 defina el broker real).

Job: `login-federado/src/main/java/citypass/loginfederado/metrics/MetricsPublisher.java`
Cron real: `0 5 0 * * *` (00:05 UTC, calcula el día que acaba de cerrar).

### 3.1 Probar sin esperar a medianoche

**a) Acelerar el cron temporalmente** en `MetricsPublisher.java`:
```java
@Scheduled(cron = "*/30 * * * * *", zone = "UTC")  // TEMPORAL: cada 30 seg
```

**b) (Opcional, para ver datos reales en vez de ceros) apuntar a hoy** en vez de ayer:
```java
LocalDate ayer = LocalDate.now(ZoneOffset.UTC); // TEMPORAL: hoy, no ayer
```

**c) Reconstruir:**
```
docker compose up --build -d app
```

**d) Generar actividad** (login + refresh + logout) para tener datos que ver:
```
curl -X POST http://localhost:8081/auth/login -H "Content-Type: application/json" -d "{\"username\": \"delegado-rec\", \"password\": \"changeit123\", \"clientId\": \"citypass-reclamos-web\"}"
curl -X POST http://localhost:8081/auth/refresh -H "Content-Type: application/json" -d "{\"refreshToken\": \"...\"}"
curl -X POST http://localhost:8081/auth/logout -H "Content-Type: application/json" -d "{\"refreshToken\": \"...\"}"
```

**e) Ver el evento publicado en el log:**
```
docker compose logs app -f | findstr /i "metricas"
```
Esperado: una línea `[EVENTO PUBLICADO] tipo=identidad.metricas.diarias payload={...}`
con `usuariosActivosDiarios`, `usuariosActivosMensuales`, `horariosLogin`,
`sesionesFinalizadas` y las duraciones reflejando la actividad generada.
Para cortar el log en vivo: `Ctrl+C` (no afecta al contenedor).

**f) Revertir los cambios temporales** antes de dar por cerrado el testing:
```java
LocalDate ayer = LocalDate.now(ZoneOffset.UTC).minusDays(1);
```
```java
@Scheduled(cron = "0 5 0 * * *", zone = "UTC")
```
```
docker compose up --build -d app
```

---

## Notas generales

- **`accessToken` expira rápido** (~15 min) — si una batería larga te tira
  `401` con `Jwt expired`, hacé login de nuevo o usá `/auth/refresh`.
- Para ver el `Caused by:` real de un error 500/503, filtrá el log en vez
  de usar `--tail` a ciegas:
  ```
  docker compose logs app | findstr /i "Caused ERROR"
  ```
- `docker compose ps -a` (con `-a`) para ver también contenedores caídos,
  no solo los que están `Up`.
