# login-federado

Módulo de Login Federado (LDAP + JWT RS256) — Proyecto CityPass+, Grupo 2.

Un IdP propio: autentica contra OpenLDAP, consulta un microservicio de
detección de anomalías antes de emitir tokens, firma JWT con RS256 y expone
JWKS para que las APIs validen sin llamar de vuelta. Incluye el backend del
panel administrativo (altas/bajas/grupos del directorio) consumiendo el mismo
contrato de tokens.

## Stack

Java 21 · Spring Boot 3 · Spring Security 6 · Spring LDAP · OpenLDAP ·
PostgreSQL · JWT (RS256) · FastAPI (microservicio de anomalías) · Docker Compose

## Arquitectura en 30 segundos

```
cliente ──POST /auth/login──▶ login-federado ──score──▶ anomaly-detection (:8000)
                                   │                        (fail-closed: si está
                                   ▼                         caído, todo login da
                              OpenLDAP (bind + memberOf)      401 genérico)
                                   │
                              PostgreSQL (refresh tokens rotativos,
                                          ventana deslizante anti fuerza bruta)
```

**Importante:** el microservicio `anomaly-detection` es obligatorio en
desarrollo. Sin él corriendo, ningún login va a funcionar (por diseño).

## Requisitos

- Docker + Docker Compose (único requisito para el modo rápido)
- JDK 21 + Maven 3.9+ (solo para correr la app fuera de Docker o los tests)

## Levantar el entorno

### Modo recomendado: todo en Docker

```bash
docker compose up -d
```

Levanta y siembra todo: OpenLDAP (con configuración y seed automáticos),
PostgreSQL, el microservicio de anomalías y esta app en `http://localhost:8081`.

La primera vez genera automáticamente el par de claves RSA en `./keys/`
(`JwtKeyConfig` las crea si no existen — solo para desarrollo; en producción
van como secrets del proveedor cloud).

### Modo alternativo: app fuera de Docker (hot reload)

```bash
docker compose up -d openldap postgres anomaly-detection
./mvnw spring-boot:run
```

Swagger UI: <http://localhost:8081/docs>

## Probar con Postman

Importar `citypass-login-federado.postman_collection.json` (raíz del repo).
62 requests en orden numérico: login, errores genéricos, refresh con rotación
y detección de reuso, JWKS, tokens de servicio, panel, lockout y anomalías.
Los scripts capturan los tokens automáticamente entre requests.

## Endpoints principales

| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/auth/login` | Autentica y emite access + refresh token |
| POST | `/auth/refresh` | Canjea refresh por nuevo par (rotación; reuso ⇒ cadena revocada) |
| GET | `/.well-known/jwks.json` | Clave pública para validar firmas |
| POST | `/oauth/token` | `client_credentials` para servicios (Basic auth) |
| GET/POST/PUT/DELETE | `/panel/**` | Backend del panel (requiere token delegado) |

Login (notar **camelCase**, así lo implementa este proyecto):

```bash
curl -X POST http://localhost:8081/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"jperez","password":"changeit123","clientId":"citypass-reclamos-web"}'
```

Token de servicio:

```bash
curl -u grupo1:grupo1-secret-dev -X POST http://localhost:8081/oauth/token \
  -H "Content-Type: application/json" \
  -d '{"audience":"citypass-platform"}'
```

## Usuarios de prueba (seed en `ldap/config/01-seed.ldif`)

Password de todos: `changeit123`

| uid | Módulo | Grupos notables |
|---|---|---|
| jperez | reclamos | soporte-n2, guardia-finde |
| soporte1 | reclamos | guardia-finde |
| consulta1 | reclamos | — |
| delegado-rec | reclamos | delegados |
| mgomez | movilidad | — |
| delegado-mov | movilidad | delegados |

## Clientes registrados (`application.yml`)

| clientId | Tipo | Audience |
|---|---|---|
| citypass-reclamos-web / movilidad-web / residuos-web / emergencias-web / espacios-web / analitica-web | human | `citypass-<módulo>-api` |
| citypass-admin-web | human transversal | `citypass-admin-api` |
| grupo1 / grupo5 | service | `citypass-platform` |

El panel requiere token con audience `citypass-admin-api`, claim `module`,
grupo `delegados`. El módulo operado sale **siempre del token**: ningún
endpoint lo acepta por parámetro (aislamiento estructural entre módulos).

## El directorio LDAP

Todo se siembra solo al primer arranque vía `ldap/config/apply-config.sh`
(idempotente: cada bloque verifica y salta con "ya aplicado").

### Árbol

```
dc=citypass,dc=local
├── ou=Movilidad ──┬── ou=People     ← personas del módulo (inetOrgPerson)
│                  └── ou=Groups     ← grupos (groupOfNames), incluye delegados
├── ou=Residuos    (misma estructura)
├── ou=Reclamos    (misma estructura)
├── ou=Emergencias (misma estructura)
├── ou=Espacios    (misma estructura)
├── ou=Analitica   (misma estructura)
└── ou=ServiceAccounts
    ├── cn=readonly                 ← busca y hace binds; no escribe, no ve hashes
    ├── cn=panel-writer             ← escritura exclusiva del backend del panel
    ├── cn=admin                    ← admin del árbol (no lo usa la app)
    └── cn=empty-group-placeholder  ← miembro técnico: ningún grupo nace vacío
```

Las cuentas de servicio viven **fuera** de las OUs de módulo: no tienen `uid`,
así estructuralmente no pueden autenticarse como personas.

### Modelo de entradas

- **Persona** (`inetOrgPerson`): `uid`, `cn`, `sn`, `givenName`, `mail`,
  `userPassword` (hasheada `{SSHA}` por el servidor — nadie ve hashes) y
  `employeeNumber` (`U######`, secuencial global asignado por el panel).
- **Grupo** (`groupOfNames`): `cn` + `member` (valores SIEMPRE DNs absolutos,
  ej. `uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local`).
- El grupo `delegados` existe **en cada módulo**, es reservado e inborrable.
- `memberOf` es operacional: se calcula solo a partir de `member` (overlay)
  y hay que pedirlo explícitamente en cada búsqueda.

### Overlays activos (`apply-config.sh`)

| Overlay | Qué garantiza |
|---|---|
| `memberof` | Calcula `memberOf`; con refint integrado limpia al borrar grupos |
| `refint` | Integridad referencial de `member` |
| `unique` | Unicidad global de `uid`, `mail` y `employeeNumber` |
| `constraint` | `member` solo acepta DNs de personas o el placeholder → anti-anidamiento de grupos |
| `ppolicy` | Hashea contraseñas en claro al vuelo (`olcPPolicyHashCleartext`) |

### ACLs (resumen)

```
{0} userPassword  : self write · anonymous auth · panel-writer =wx · * none
{1} memberOf      : legible por todos
{2} ServiceAccounts: solo admin/readonly/panel-writer
{3} todo lo demás : admin write · panel-writer write · readonly read
                    · self read · anonymous auth
```

Efecto práctico: ni readonly ni nadie puede leer hashes; el login autentica
por bind (el servidor valida la contraseña, la app nunca la ve); el
panel-writer puede escribir el árbol pero tampoco lee `userPassword`.


## Notas operativas

- **Lockout**: 5 intentos fallidos en 15 minutos bloquean al usuario
  (respuesta indistinguible de credenciales inválidas). Desbloquear:

  ```bash
  docker exec citypass-db psql -U citypass -d login_federado \
    -c "DELETE FROM login_attempts WHERE username='soporte1';"
  ```

- **Reiniciar la app borra sesiones activas**: `schema.sql` recrea las tablas
  en cada arranque (modo dev). Los refresh tokens vivos mueren.
- **Editar archivos bajo `ldap/config/`**: deben tener fin de línea LF. El
  `.gitattributes` del repo lo fuerza — respetarlo o el bash del contenedor
  falla con `$'\r': command not found`.

## Estructura de paquetes

```
config/      Beans de Spring Security, LDAP (readonly + panel-writer), JWT y claves
controller/  AuthController (/auth), OAuthTokenController (/oauth/token), JwksController
service/     AuthService, RefreshTokenService (rotación/reuso), LoginAttemptService
identity/    LdapDirectory (búsqueda + bind) y ClientRegistry (clientes registrados)
token/       Emisión de access tokens y claims (contrato §3)
security/    Filtros JWT y AnomalyRiskClient (fail-closed)
panel/       PanelController, autorización delegada, escritura al directorio, auditoría
event/       Publicación de eventos de autenticación
exception/   Manejo centralizado: TODA falla de auth responde el mismo cuerpo
dto/ model/ repository/
```

## Tests

```bash
./mvnw test
```

Tests unitarios (JUnit 5 + Mockito) sobre emisión de tokens, reglas del panel,
rotación de refresh y registro de clientes. Testcontainers figura como
dependencia para integración con OpenLDAP/PostgreSQL reales.
