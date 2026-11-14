# CityPass+ — Identidad, autenticación y accesos (Squad 2)

Este repositorio contiene el backend de identidad de CityPass+: el servicio Java `login-federado`, el microservicio Python `anomaly-detection` y la infraestructura local de OpenLDAP y PostgreSQL. El servicio autentica personas por bind LDAP, emite JWT RS256 y refresh tokens opacos rotativos, expone JWKS, gestiona personas y grupos del directorio y registra intentos y mutaciones administrativas.

> Estado auditado: 14 de noviembre de 2026. Esta documentación describe el código presente; los faltantes se declaran explícitamente.

## Estructura

```text
.
├── login-federado/       # API Spring Boot, configuración LDAP, SQL y tests
├── anomaly-detection/    # API FastAPI de scoring de riesgo
├── .github/workflows/    # build, tests y escaneos
└── login-federado/docs/  # fuente única de verdad técnica del repositorio
```

## Stack exacto

| Componente | Versión/configuración comprobada |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.13 |
| Spring Security / LDAP / Data JPA | gestionados por Spring Boot 3.3.13 |
| PostgreSQL JDBC | 42.7.13 |
| OpenLDAP | `osixia/openldap:1.5.0` |
| PostgreSQL | `postgres:16-alpine` |
| Python | 3.12 |
| FastAPI / Uvicorn | 0.115.0 / 0.30.6 |
| scikit-learn | 1.5.2 |
| pandas / NumPy | 2.2.3 / 2.1.1 |
| Contenedores | Dockerfiles multi-stage y Docker Compose |

## Inicio rápido

Requisito: Docker con Compose. Desde `login-federado/`:

```bash
docker compose up -d --build
docker compose ps
```

Servicios publicados:

| Servicio | Puerto host | Uso |
|---|---:|---|
| `app` | 8081 | API, Swagger UI en `/docs`, health en `/actuator/health` |
| `anomaly-detection` | 8000 | `GET /health`, `POST /score` |
| `openldap` | 389 / 636 | LDAP / LDAPS |
| `postgres` | 5432 | base `login_federado` |

El contenedor `ldap-config` es un job one-shot que configura overlays, ACL, índices y seed después del healthcheck de OpenLDAP.

### Credenciales locales de desarrollo

| Recurso | Usuario | Contraseña |
|---|---|---|
| PostgreSQL | `citypass` | `citypass` |
| Administrador LDAP | `cn=admin,dc=citypass,dc=local` | `admin` |
| Personas seed | `jperez`, `mgomez`, `delegado-rec`, `soporte1`, `consulta1`, `delegado-mov` | `changeit123` |
| Servicio OAuth | `grupo1` | `grupo1-secret-dev` |

Para entrar al panel use `delegado-rec` o `delegado-mov` con el cliente `citypass-admin-web`. Las credenciales son solo para desarrollo.

### Variables de entorno

| Variable | Default local | Propósito |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/login_federado` | conexión de la API a PostgreSQL |
| `DB_USERNAME` / `DB_PASSWORD` | `citypass` / `citypass` | credenciales SQL |
| `SPRING_LDAP_URLS` | `ldap://localhost:389` | servidor LDAP |
| `SPRING_LDAP_USERNAME` | cuenta `readonly` | bind de lectura |
| `LDAP_READONLY_PASSWORD` o `SPRING_LDAP_PASSWORD` | `readonly-secret` | secreto de lectura; Compose usa la propiedad Spring |
| `PANEL_LDAP_PASSWORD` | `panel-writer-secret` | escritura del panel |
| `ANOMALY_SERVICE_URL` | `http://localhost:8000` | scoring de riesgo |
| `ANOMALY_DB_URL` | sin default | SQLAlchemy del microservicio |
| `SVC_GRUPO1_SECRET`, `SVC_GRUPO5_SECRET` | secretos dev en `application.yml` | clientes de servicio |

Las claves RSA se generan en `login-federado/keys/` si faltan. Es una comodidad de desarrollo; producción debe montar claves estables desde un gestor de secretos.

## Prueba rápida

```bash
curl -X POST http://localhost:8081/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"delegado-rec","password":"changeit123","clientId":"citypass-admin-web"}'
```

Rutas implementadas: `/auth/login`, `/auth/refresh`, `/auth/logout`, `/oauth/token`, `/.well-known/jwks.json` y las rutas `/panel/personas/**` y `/panel/grupos/**` detalladas en Swagger.

## Tests y calidad

Desde `login-federado/`, con JDK 21 y Maven 3.9+ instalados:

```bash
mvn clean test
mvn clean verify
```

Hay 32 métodos `@Test` unitarios sobre emisión JWT, refresh, LDAP, registro de clientes y reglas del panel. Testcontainers está declarado, pero no hay tests de integración Testcontainers en el árbol auditado. Tampoco hay plugin JaCoCo ni umbral de cobertura configurado, por lo que el repositorio no demuestra todavía el objetivo de cobertura de 60 %.

El pipeline `.github/workflows/pr-checks.yml` ejecuta `mvn -B clean verify`, publica reportes Surefire y corre Gitleaks y Trivy. No existe workflow de smoke tests ni despliegue cloud del backend.

## Estado real de ML y EDA

- `anomaly-detection` calcula cuatro features desde `login_attempts`. Si existe `models/isolation_forest.pkl`, usa `IsolationForest`; el repositorio solo versiona `.gitkeep`, por lo que el contenedor actual usa el fallback de reglas.
- `train.py` es un esqueleto no ejecutable porque `X` no está construido.
- `EventPublisher` está desacoplado, pero la única implementación es `LoggingEventPublisher`: serializa `usuario.autenticado` al log. No hay broker, topic, reintentos, outbox ni consumidores.

## Compatibilidad con el frontend

El frontend auditado consume rutas inglesas (`/panel/people`, `/panel/groups`, `/members`), mientras este backend publica rutas españolas (`/panel/personas`, `/panel/grupos`, `/miembros`). También hay pantallas frontend para recuperación de contraseña y operaciones masivas que no tienen endpoints backend. La autenticación básica sí comparte contrato. Consulte la matriz de brechas en [rúbrica y defensa técnica](login-federado/docs/rubrica-evaluacion.md).

## Documentación

- [Arquitectura y despliegue](login-federado/docs/diagrams/01-c4-contexto.md)
- [Casos de uso](login-federado/docs/diagrams/07-casos-de-uso.md)
- [Interacciones](login-federado/docs/diagrams/04-sequence-login.md)
- [Modelo de datos](login-federado/docs/diagrams/08-entidad-relacion.md)
- [ADRs](login-federado/docs/adr/README.md)
- [Rúbrica, evidencias y brechas](login-federado/docs/rubrica-evaluacion.md)

La documentación oficial vigente está en `login-federado/docs/`. Los archivos anteriores de esa misma carpeta se conservan como antecedentes y están identificados en sus índices.
