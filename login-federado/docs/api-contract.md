# Contrato HTTP y autorización

**Última revisión:** 19 de noviembre de 2026

## Fuentes de verdad

1. La especificación generada en ejecución: `GET /v3/api-docs`.
2. Swagger UI: `GET /docs`.
3. Snapshot versionado: `login-federado/swagger.json`.
4. Casos manuales y negativos: colección Postman y `Guia_Testing_Login_Federado.md`.

## Superficies de la API

| Área | Operaciones | Autorización |
|---|---|---|
| Salud y claves | `/health`, `/actuator/health`, `/.well-known/jwks.json` | Público |
| Autenticación humana | `/auth/login`, `/auth/refresh`, `/auth/logout` | Público; credenciales o refresh según operación |
| Contraseña | `/auth/forgot-password`, `/auth/reset-password`, `/me/change-password` | Recupero público anti-enumeración; cambio autenticado |
| Perfil | `GET /me` | JWT humano |
| OAuth de servicio | `POST /oauth/token` | `client_credentials`, Basic o formulario |
| Personas y grupos | `/panel/people`, `/panel/groups`, membresías y módulos | Delegado de su módulo o `admin-global` |
| Auditoría y anomalías | `/panel/audit`, `/panel/anomaly-events`, aprobación | Delegado/admin según aislamiento de módulo |
| Analítica | `GET /admin/analitica` | Exclusivo de `admin-global`; proxy a Grupo 8 |

## Invariantes de seguridad

- Los JWT se firman con RS256 e incluyen `kid`; las APIs consumidoras validan mediante JWKS.
- Los tokens humanos y de servicio no son intercambiables: `token_use`, audiencia y namespace delimitan su uso.
- Los refresh tokens son opacos, se almacenan hasheados, rotan y detectan reutilización.
- Login y recupero evitan enumerar usuarios mediante respuestas uniformes.
- El panel aplica aislamiento por módulo; el frontend no reemplaza la autorización del backend.
- Analítica recibe el Bearer humano a través del proxy; el token no se coloca en la URL ni se entrega al `iframe`.

## Convenciones de error

- `400`: petición inválida o validación de formato.
- `401`: autenticación ausente o inválida.
- `403`: identidad válida sin permiso suficiente.
- `404`: recurso no visible o inexistente.
- `409`: conflicto de estado.
- `422`: operación semánticamente inválida, especialmente contraseñas.
- `429`: rate limit.
- `502`: upstream respondió con contenido exitoso inválido.
- `503`: dependencia externa no disponible.

## Regla de actualización

Todo cambio de controller debe actualizar en el mismo PR sus pruebas, el snapshot OpenAPI o su justificación, la colección Postman si es demostrable manualmente y los diagramas afectados.
