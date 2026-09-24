# 🏙️ CityPass+ | Módulo 2: Login Federado

**Plataforma de Servicios Urbanos Inteligentes**

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://java.com)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![OpenLDAP](https://img.shields.io/badge/OpenLDAP-Security-blue.svg)](https://www.openldap.org/)
[![JWT](https://img.shields.io/badge/JWT-RS256-black.svg)](https://jwt.io)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED.svg)](https://www.docker.com/)
[![UADE](https://img.shields.io/badge/UADE-DesApp_II-004d99.svg)]()

---

## 📖 Descripción del Módulo

El **Módulo de Login Federado (LDAP + JWT)** es el núcleo de seguridad y gestión de
identidad de la plataforma **CityPass+**. Centraliza la autenticación de todos los
usuarios de la ciudad interactuando con un directorio de identidades corporativo
(OpenLDAP) y emitiendo tokens de acceso seguros (JWT con firma asimétrica RS256)
para proteger los endpoints de los 7 módulos restantes.

Es un **IdP propio**: autentica contra OpenLDAP (bind + `memberOf`), consulta un
microservicio de detección de anomalías antes de emitir tokens (fail-closed), firma
JWT con RS256 y expone JWKS para que las APIs validen sin llamar de vuelta. Incluye
el backend del panel administrativo (altas/bajas/grupos del directorio) que consume
el mismo contrato de tokens, y los flujos self-service de recupero de contraseña por
token de un solo uso.

Este proyecto forma parte de la asignatura **Desarrollo de Aplicaciones II (2c 2026)**, dictada por el profesor Andrés Sacco en la Universidad Argentina de la Empresa (UADE).

---

## 👥 Equipo de Trabajo (Grupo 2)

| Integrante | Rol | Módulo |
| :--- | :--- | :--- |
| **Abeledo, Federico** | Project Manager (PM) | Login Federado |
| **Francisco Frate, Delfina** | Scrum Master | Login Federado |
| **Hernandez, Nicolas** | Backend | Login Federado |
| **Opatich, Ignacio** | Frontend | Login Federado |
| **Ravaschio, Guido** | DevOps | Login Federado |
| **Wu, Antonio** | Security / Backend | Login Federado |

---

## 🏗️ Alineación con la Rúbrica (Evaluación)

Nuestro desarrollo está diseñado para cumplir con los estándares técnicos exigidos en la materia:

- 📐 **Arquitectura y Modelado:** Documentación de decisiones (ADRs), diagramas C4 y modelo Entidad-Relación.
- 🔐 **Seguridad Avanzada:** Autenticación LDAP, tokens JWT firmados asimétricamente (RS256), validación de claims, anti-enumeración, lockout anti fuerza bruta y contratos de seguridad documentados (diseño, token y anomalías).
- 📨 **Event Driven Architecture (EDA):** Eventos de autenticación/auditoría publicados vía abstracción de publisher — default `logging`, con envío HTTP a la gateway de la plataforma implementado y a la espera del contrato del Grupo 1 (RabbitMQ/Kafka quedan como evolución, ver ADR-006).
- 🧪 **Testing Integrado:** Pruebas unitarias e integrales con JUnit 5, Mockito y Testcontainers; jacoco exige >60% de cobertura en línea en `mvn verify`.
- 🚀 **DevOps & Cloud:** Entorno local 100% dockerizado (`docker-compose`), flujos CI/CD con GitHub Actions y despliegue cloud.
- 🧠 **Innovación (IA/I+D):** Lógica de detección de anomalías en los intentos de inicio de sesión (microservicio `anomaly-detection` con IsolationForest, integrado fail-closed).
- 📱 **UX/UI:** Interfaz Frontend desarrollada en React orientada a una experiencia de autenticación fluida.
- 🔄 **Gestión Ágil:** Framework Scrum, seguimiento con Jira y control de versiones bajo políticas de Git Flow.

---

## 🛠️ Stack Tecnológico

* **Backend:** Java 21, Spring Boot 3, Spring LDAP, Spring Security 6.
* **Directorio de Identidad:** OpenLDAP (overlays `memberof`, `refint`, `unique`, `constraint`, `ppolicy`).
* **Base de Datos:** PostgreSQL.
* **Firma Asimétrica:** JWT (Algoritmo RS256).
* **IA:** FastAPI + IsolationForest (microservicio `anomaly-detection`).
* **Frontend:** React 18, Vite, TypeScript, Tailwind CSS.
* **Testing:** JUnit 5, Mockito, Testcontainers.
* **Infraestructura:** Docker, Docker Compose, GitHub Actions.

---

## 📐 Arquitectura en 30 segundos

```
cliente ──POST /auth/login──▶ login-federado ──POST /score──▶ anomaly-detection (:8000)
                                   │                        (fail-closed: si está
                                   ▼                         caído, todo login da
                              OpenLDAP (bind + memberOf)      401 genérico)
                                   │
                              PostgreSQL (refresh tokens rotativos,
                                          ventana deslizante anti fuerza bruta)
```

**Importante:** el microservicio `anomaly-detection` es obligatorio en desarrollo:
sin él corriendo, ningún login funciona (por diseño, fail-closed). El modelado
(`IsolationForest`, entrenado con datos sintéticos y métricas P 79% / R 78% / F1 79%)
está validado como PoC en el repo `anomaly-detection/`; el empaquetado del artefacto
para el contenedor está documentado en ADR-009.

## Requisitos

- Docker + Docker Compose (único requisito para el modo rápido)
- JDK 21 + Maven 3.9+ (solo para correr la app fuera de Docker o los tests)

---

## 🚀 Levantar el entorno

El compose vive en `login-federado/docker-compose.yml` (el entorno se levanta desde esa carpeta).

### Modo recomendado: todo en Docker

```bash
cd login-federado
docker compose up -d
```

Levanta y siembra todo: OpenLDAP (configuración + seed automáticos e idempotentes),
PostgreSQL, el microservicio de anomalías y esta app en `http://localhost:8081`.

La primera vez genera automáticamente el par de claves RSA en `login-federado/keys/`
(`JwtKeyConfig` las crea si no existen — solo para desarrollo; en producción van como
secrets del proveedor cloud y `auto-generate-keys` se apaga).

### Modo alternativo: app fuera de Docker (hot reload)

```bash
cd login-federado
docker compose up -d openldap postgres anomaly-detection
./mvnw spring-boot:run
```

Swagger UI: <http://localhost:8081/docs>

---

## 🔍 Documentación de la API (Swagger)

La documentación interactiva la genera **SpringDoc** (OpenAPI 3.0) a partir de los
controllers, sin mantenimiento manual.

- **Swagger UI**: <http://localhost:8081/docs> — UI interactiva: explorar cada endpoint, ver cuerpos de request/response y probarlo desde el navegador.
- **Especificación JSON**: <http://localhost:8081/v3/api-docs> — OpenAPI crudo (mismo contenido que ve la UI). Se puede importar en [swagger.io](https://swagger.io/) o Postman con "Import from URL".
- **`login-federado/swagger.json`**: snapshot estático de la misma especificación, para importarla sin levantar el servicio.

Usar el botón **Authorize** (arriba a la derecha) con un access token (`Bearer <token>`) para probar los endpoints protegidos:

- `/panel/**` exige token de **delegado** (audience `citypass-admin-api`, grupo `delegados`, claim `module`) o de **admin global** (grupo `admin-global`).
- `/me` y `/me/change-password` exigen access token de persona (`token_use=human`).
- `/auth/**`, `/oauth/token`, `/.well-known/jwks.json` y `/health` son públicos.

Las 27 rutas del servicio quedan auto-documentadas (5 de `/auth`, perfil, JWKS, token
de servicio, health y el ABM del panel).

## Probar con Postman

Importar `login-federado/citypass-login-federado.postman_collection.json`.
**102 requests** en orden numérico: login, errores genéricos, refresh con rotación y
detección de reuso, JWKS, tokens de servicio, panel (incluye admin global y módulos),
lockout y anomalías. Los scripts capturan los tokens automáticamente entre requests.

---

## 🔌 Endpoints principales

| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/auth/login` | Autentica y emite access + refresh token |
| POST | `/auth/refresh` | Canjea refresh por nuevo par (rotación; reuso ⇒ cadena revocada) |
| POST | `/auth/logout` | Revoca la sesión (refresh token) — 204 siempre |
| POST | `/auth/forgot-password` | Emite token de recupero de un solo uso y lo manda por mail (nunca escribe LDAP) |
| POST | `/auth/reset-password` | Canjea el token y define la nueva contraseña en LDAP; revoca sesiones |
| GET | `/me` | Perfil de la persona autenticada (uid, nombre, mail, módulo, grupos) |
| POST | `/me/change-password` | Cambia la propia contraseña (actual + JWT); revoca todas las sesiones |
| GET | `/.well-known/jwks.json` | Clave pública para validar firmas RS256 |
| POST | `/oauth/token` | `client_credentials` para servicios (Basic auth, form-urlencoded) |
| GET | `/health` | Healthcheck de la aplicación |
| GET/POST/PUT/DELETE | `/panel/**` | Backend del panel (delegados + admin global; también `/modules` y `/admin/*`) |

Login (notar **camelCase**, así lo implementa este proyecto):

```bash
curl -X POST http://localhost:8081/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"jperez","password":"changeit123","clientId":"citypass-reclamos-web"}'
```

### Recupero y cambio de contraseña (self-service)

Flujo por **token de un solo uso**: la solicitud NUNCA escribe en LDAP, solo persiste
el hash de un token y envía un enlace por mail. LDAP se toca recién al canjearlo.

```bash
# 1) Pedir recupero (SIEMPRE 204, exista o no el usuario: anti-enumeración)
curl -X POST http://localhost:8081/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"uid":"jperez"}'

# 2) Canjear el enlace del mail con la nueva contraseña (204; 422 si venció/inválido)
curl -X POST http://localhost:8081/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token":"<token del enlace>","newPassword":"nuevaClaveFu553"}'

# 3) Cambio de contraseña estando logueado (requiere access token Bearer)
curl -X POST http://localhost:8081/me/change-password \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{"currentPassword":"nuevaClaveFu553","newPassword":"otraClaveSegura9"}'
```

Al cambiar o restablecer la contraseña se revocan **todas** las sesiones (refresh
tokens) de la persona; el access token vigente sigue vivo hasta su expiración (igual
que en la baja del panel).

**SMTP:** sin `spring.mail.host` el envío cae en modo dev: el enlace se imprime en
consola (`app.password-reset.debug-log: true`, default). Para enviar de verdad, copiar
`login-federado/.env.example` como `login-federado/.env` y completar las variables
`SPRING_MAIL_*` (guía de Gmail adentro); en producción además poner
`PASSWORD_RESET_DEBUG_LOG=false`. Ninguna falla de envío se propaga al cliente: la
respuesta es 204 y el detalle va al log. Las variables incluyen topes anti-abuso
(cooldown 5 min, máx 3/h por cuenta, 50/h por IP).

### Token de servicio (backend a backend)

`/oauth/token` consume **`application/x-www-form-urlencoded`** (no JSON) y solo admite
`grant_type=client_credentials`:

```bash
curl -u grupo2:grupo2-secret-dev -X POST http://localhost:8081/oauth/token \
  -d "grant_type=client_credentials"
```

Los secretos de dev son `group1-secret-dev`, `group5-secret-dev` y `grupo2-secret-dev`
(override con `SVC_*_SECRET`). El token emitido NO trae `groups` ni `module`: su
identidad es el namespace.

---

## 👤 Usuarios de prueba (seed en `login-federado/ldap/config/01-seed.ldif`)

Password de todos: `changeit123`

| uid | Módulo | Grupos notables |
|---|---|---|
| jperez | reclamos | soporte-n2, guardia-finde |
| soporte1 | reclamos | guardia-finde |
| consulta1 | reclamos | — |
| delegado-rec | reclamos | delegados |
| mgomez | movilidad | — |
| delegado-mov | movilidad | delegados |
| delegado-eda | eda | delegados |
| admin-global | admin (transversal) | admin-global |

## 🔑 Clientes registrados (`login-federado/src/main/resources/application.yml`)

| clientId | Tipo | Audience / Módulo |
|---|---|---|
| citypass-movilidad-web / residuos-web / reclamos-web / emergencias-web / espacios-web / analitica-web / eda-web | human | `citypass-<módulo>-api` |
| citypass-admin-web | human transversal | `citypass-admin-api` (único cliente que cruza módulos) |
| group1 | service | audience `citypass` · namespace `com.citypass.bus` |
| group5 | service | audience `citypass` · namespace `com.citypass.analitica` |
| grupo2 | service | audience `citypass` · namespace `com.citypass.auth` |

El panel exige token de persona con audience `citypass-admin-api` (`token_use=human`):
los **delegados** operan el módulo de su claim `module` (nadie acepta el módulo por
parámetro: aislamiento estructural); los **admins globales** operan con `?module=` en
los endpoints regulares o sin módulo en `/panel/admin/**` (visión global del directorio).

---

## 📁 El directorio LDAP

Todo se siembra al primer arranque vía `login-federado/ldap/config/apply-config.sh`
(idempotente: cada bloque verifica y salta con "ya aplicado").

### Árbol

```
dc=citypass,dc=local
├── ou=admin          (misma estructura; rol global, ej. admin-global)
├── ou=Movilidad ──┬── ou=People     ← personas del módulo (inetOrgPerson)
│                  └── ou=Groups     ← grupos (groupOfNames), incluye delegados
├── ou=Residuos    (misma estructura)
├── ou=Reclamos    (misma estructura)
├── ou=Emergencias (misma estructura)
├── ou=Espacios    (misma estructura)
├── ou=Analitica   (misma estructura)
├── ou=Eda         (misma estructura)
└── ou=ServiceAccounts
    ├── cn=readonly                 ← busca y hace binds; no escribe, no ve hashes
    ├── cn=panel-writer             ← escritura exclusiva del backend del panel
    ├── cn=admin                    ← admin del árbol (no lo usa la app)
    └── cn=empty-group-placeholder  ← miembro técnico: ningún grupo nace vacío
```

Las cuentas de servicio viven **fuera** de las OUs de módulo: no tienen `uid`, así
estructuralmente no pueden autenticarse como personas.

### Modelo de entradas

- **Persona** (`inetOrgPerson`): `uid`, `cn`, `sn`, `givenName`, `mail`, `userPassword`
  (hasheada `{SSHA}` por el servidor — nadie ve hashes) y `employeeNumber` (`U######`,
  secuencial global asignado por el panel).
- **Grupo** (`groupOfNames`): `cn` + `member` (valores SIEMPRE DNs absolutos, ej.
  `uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local`).
- El grupo `delegados` existe **en cada módulo** y es reservado (no debe eliminarse;
  el backend no lo protege explícitamente hoy).
- `memberOf` es operacional: se calcula solo a partir de `member` (overlay) y hay que
  pedirlo explícitamente en cada búsqueda.

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

Efecto práctico: ni readonly ni nadie puede leer hashes; el login autentica por bind
(el servidor valida la contraseña, la app nunca la ve); el panel-writer puede escribir
el árbol pero tampoco lee `userPassword`.

---

## ⚙️ Notas operativas

- **Lockout**: 5 intentos fallidos en 15 minutos bloquean al usuario (respuesta
  indistinguible de credenciales inválidas). Desbloquear:

  ```bash
  docker exec citypass-db psql -U citypass -d login_federado \
    -c "DELETE FROM login_attempts WHERE username='soporte1';"
  ```

- **Los datos NO se pierden al reiniciar**: `schema.sql` es idempotente (sin `DROP`);
  las tablas y los refresh tokens sobreviven al reinicio de la app. Para resetear el
  entorno de desarrollo (vuelve a sembrar LDAP y vacía Postgres):

  ```bash
  cd login-federado && docker compose down -v && docker compose up -d
  ```

- **EDA**: los eventos de autenticación/auditoría salen por la abstracción
  `EventPublisher`. Default `logging`; seteando `EDA_PUBLISHER=http` y
  `EDA_GATEWAY_URL` se envían a la gateway de la plataforma (contrato del Grupo 1
  pendiente de definir — ADR-006). El token de servicio del publisher usa `grupo2`.
- **Editar archivos bajo `login-federado/ldap/config/`**: deben tener fin de línea LF.
  El `.gitattributes` del repo lo fuerza — respetarlo o el bash del contenedor falla
  con `$'\r': command not found`.

---

## 🗂️ Estructura de paquetes (`login-federado/src/main/java/citypass/loginfederado/`)

```
config/      Beans de Spring Security, LDAP (readonly + panel-writer), JWT y claves
controller/  AuthController (/auth), OAuthTokenController (/oauth/token), ProfileController (/me),
             JwksController, HealthController
service/     AuthService, RefreshTokenService (rotación/reuso), LoginAttemptService, PasswordService
identity/    LdapDirectory (búsqueda + bind) y ClientRegistry (clientes registrados)
token/       Emisión de access tokens y claims (contrato §3)
security/    Filtros JWT y AnomalyRiskClient (fail-closed)
panel/       PanelController, autorización delegada, escritura al directorio, auditoría
event/       Publicación de eventos (EventPublisher: logging / HTTP) y HealthController
metrics/     Persistencia de intentos de login (ventana anti fuerza bruta)
exception/   Manejo centralizado: TODA falla de auth responde el mismo cuerpo
dto/ model/ repository/
```

---

## 🧪 Tests

```bash
cd login-federado
./mvnw clean verify
```

- Unit tests (JUnit 5 + Mockito) sobre emisión de tokens, claims, reglas del panel,
  rotación/reuso de refresh y registro de clientes.
- Testcontainers para integración real con OpenLDAP y PostgreSQL.
- El perfil `verify` aplica la regla **jacoco ≥ 60% cobertura en línea** (fail si baja).
- Reporte de cobertura: `login-federado/target/site/jacoco/index.html`.
- En CI, GitHub Actions corre la suite completa y publica la cobertura a SonarCloud.