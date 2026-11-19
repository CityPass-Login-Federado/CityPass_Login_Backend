# Integración con otros squads

Los seis módulos funcionales usan el Login Federado en producción. Al 18/11/2026, sus frontends suman 1.074 sesiones de login, y los delegados de cada uno administran su propia gente desde nuestro panel. Todos los datos de este documento salen de la base de producción del IdP y de su configuración; no dependen de lo que informe cada grupo.

## Qué habilitamos para cada módulo

Cada módulo recibió un cliente web para el login de su personal (JWT humano con `aud` y `groups`), una cuenta de servicio para obtener tokens por `client_credentials` y publicar en el bus, y el origen de su frontend en CORS.

| Módulo | Cliente web | Cuenta de servicio (namespace) | Origen en CORS | Tarea |
| --- | --- | --- | --- | --- |
| Movilidad | `citypass-movilidad-web` | `grupo3` (`com.citypass.movilidad`) | `daii-grupo-8-frontend.vercel.app` | TASK-150 |
| Reclamos | `citypass-reclamos-web` | `grupo4` (`com.citypass.reclamos`) | `reclamos-frontend-g5.vercel.app` | TASK-153 |
| Emergencias | `citypass-emergencias-web` | `grupo5` (`com.citypass.emergencias`) | `frontend-krds.onrender.com` | TASK-149 |
| Residuos | `citypass-residuos-web` | `grupo6` (`com.citypass.residuos`) | `citypass-residuos-frontend.onrender.com` | TASK-151 |
| Espacios Públicos | `citypass-espacios-web` | `grupo7` (`com.citypass.espacios`) | `espacios-publicos-frontend.vercel.app` | TASK-149 |
| Analítica | `citypass-analitica-web` | `grupo8` (`com.citypass.analitica`) | `citypass-frontend-prod` y `citypass-frontend-test` (Azure) | TASK-152 |
| EDA | `citypass-eda-web`, `citypass-bus-web` | `grupo1` (`com.citypass.bus`) | `test.citypass.mrfranco.net.ar` | TASK-147, TASK-154 |

Fuente: `login-federado/src/main/resources/application.yml` (clientes y `cors.allowed-origins`). Cada origen se agregó en el commit de integración con ese módulo.

Además del cliente y la cuenta, cada módulo tiene su propia OU en LDAP con un grupo `delegados`, para que su responsable administre altas, bajas y grupos de su gente sin intervención nuestra.

## Uso del login por módulo

| Cliente web | Personas | Sesiones | Sesiones de usuarios propios del módulo | Primera | Última |
| --- | --- | --- | --- | --- | --- |
| `citypass-residuos-web` | 2 | 237 | 237 | 01/11 | 18/11 |
| `citypass-emergencias-web` | 3 | 212 | 212 | 22/10 | 18/11 |
| `citypass-espacios-web` | 2 | 188 | 188 | 15/10 | 18/11 |
| `citypass-movilidad-web` | 4 | 172 | 117 | 21/10 | 18/11 |
| `citypass-reclamos-web` | 4 | 134 | 82 | 21/10 | 18/11 |
| `citypass-analitica-web` | 2 | 131 | 131 | 02/11 | 18/11 |
| `citypass-admin-web` (nuestro panel) | 15 | 302 | 98 | 23/09 | 18/11 |

Una sesión es una cadena de refresh tokens (`chain_id`): un login y sus renovaciones. Los "usuarios propios del módulo" son los que no vienen de la carga inicial de LDAP (`U000001` a `U000008`); los crearon los delegados de cada módulo o el admin global para ese módulo. En Movilidad y Reclamos la diferencia corresponde a cuentas de la carga inicial (`mgomez`, `delegado-mov`, `jperez`, `delegado-rec`).

Consulta: `refresh_tokens` agrupado por `client_id` y `sub`.

## Uso del panel por los delegados

| Módulo | Delegados | Acciones | Tipos de acción | Primera | Última |
| --- | --- | --- | --- | --- | --- |
| Reclamos | `delegado-rec` | 36 | 10 | 23/09 | 01/11 |
| Emergencias | `delegado-emer` | 16 | 7 | 22/10 | 15/11 |
| Movilidad | `delegado-mov` | 8 | 6 | 30/10 | 06/11 |
| Analítica | `delegado-analitica`, `analitica2.user` | 6 | 5 | 20/10 | 10/11 |
| Espacios Públicos | `delegado-esp` | 3 | 3 | 21/10 | 21/10 |
| Residuos | `delegado-res` | 3 | 3 | 03/11 | 03/11 |

Las acciones incluyen alta y modificación de personas, creación de grupos, asignación de miembros, bajas con revocación de sesiones y reseteo de contraseña. Se excluyen las del `admin-global`, que es nuestro.

Consulta: `panel_audit` agrupado por `module`, `actor_uid` y `action`.

## Casos de uso transversales

Los casos de uso transversales se diseñaron con los demás squads en el Sprint 3. Cada integrante del equipo tomó uno para analizar el rol del IdP:

| Caso de uso | Tarea |
| --- | --- |
| Evento climático que afecta múltiples servicios | TASK-132 |
| Reclamo ciudadano que desencadena una intervención urbana | TASK-133 |
| Recolección inteligente después de un evento masivo | TASK-134 |
| Ruta urbana inteligente con incidentes en tiempo real | TASK-135 |
| Operativo urbano ante una concentración masiva de personas | TASK-136 |
| Reserva de espacio público + movilidad + residuos | TASK-137 |

La revisión y el cierre de estos casos con los otros squads quedó en TASK-170.

En todos el Login Federado cumple el mismo rol. Autentica al personal municipal de cada módulo que participa y emite el JWT que sus APIs validan contra nuestro JWKS. Los servicios de cada módulo obtienen su token por `client_credentials` para publicar en el bus de EDA. El IdP publica en el bus sus propios eventos de autenticación (`LoginExitoso`, `TokenRenovado`, `SesionCerrada`) mediante un outbox (ADR-010).

## Criterios y limitaciones

- Datos consultados en producción el 18/11/2026. La base guarda las fechas en UTC; en este documento están en hora de Buenos Aires.
- Los tokens de servicio (`client_credentials`) no se persisten, así que no hay conteo propio de su uso por módulo.
- Los grupos integraron con distintas combinaciones de login humano, cuenta de servicio y panel. Las guías de integración (token humano y token de servicio) se entregaron a todos.
