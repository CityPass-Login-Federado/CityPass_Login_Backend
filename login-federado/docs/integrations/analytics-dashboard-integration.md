# Integración del dashboard de Analítica

## Resumen

Este documento registra la integración entre Login Federado y el dashboard HTML
de Analítica para Identidad. Incluye el alcance técnico implementado en el
backend, las decisiones de seguridad, las validaciones realizadas y la
cronología de las dependencias externas que condicionaron el trabajo.

El resultado final expone el dashboard mediante:

```http
GET https://api.citypass-login.pp.ua/admin/analitica
```

El frontend consume exclusivamente ese endpoint. No se comunica directamente
con el servicio de Analítica.

## Contexto y cronología

Las fechas y horas de esta sección corresponden a Argentina
(`America/Buenos_Aires`) y son aproximadas.

| Fecha | Situación |
| --- | --- |
| Domingo 15 de noviembre de 2026, por la noche | Se recibió el documento de integración de Analítica, muy cerca de la fecha de entrega. Hasta ese momento no se contaba con el contrato definitivo necesario para cerrar la integración. |
| Domingo 15 y lunes 16 de noviembre | El servicio entregado inicialmente no estaba listo para ser integrado: el endpoint no devolvía el HTML definitivo del dashboard. En las primeras pruebas se obtenía una respuesta de estado, pero no el documento HTML autocontenido requerido por el frontend. |
| Alrededor de las 00:00 al cierre del lunes 16, inicio del martes 17 | Analítica comenzó a entregar el HTML definitivo desde `/embed/identidad`. Recién a partir de ese momento fue posible validar de punta a punta el proxy, las cabeceras y el contenido real. |
| Martes 17 y miércoles 18 de noviembre | El dashboard se mostraba correctamente a través del frontend, pero el indicador **“Inicios de sesión exitosos”** permanecía en `18` a pesar de generar nuevos logins en producción. Se verificó que Login Federado persistía los intentos exitosos y generaba los eventos correspondientes en el outbox. |
| Miércoles 18 de noviembre, por la noche | Los datos del dashboard comenzaron a actualizarse. Se confirmó que la falta de actualización pertenecía al lado de Analítica. No se comunicó una causa raíz técnica detallada. No fue necesario modificar el proxy de Login Federado para resolver este punto. |

Esta secuencia redujo considerablemente el tiempo disponible para integrar y
validar el comportamiento real. Antes de recibir el HTML definitivo sólo era
posible preparar el contrato esperado y realizar pruebas con respuestas
simuladas.

## Alcance de la integración

La solución se implementó como un proxy autenticado dentro de Login Federado.
Sus responsabilidades son:

1. Validar el access token recibido por el backend.
2. Autorizar únicamente a administradores globales.
3. Reenviar el mismo token humano al servicio de Analítica.
4. Solicitar el HTML de Identidad.
5. Devolver al frontend los bytes y las cabeceras permitidas sin interpretar ni
   modificar el documento.

El flujo resultante es:

```text
Frontend administrativo
        |
        | GET /admin/analitica
        | Authorization: Bearer <access-token humano>
        v
Login Federado
        |
        | GET /embed/identidad
        | Authorization: Bearer <mismo access-token>
        | Accept: text/html
        v
Servicio de Analítica
```

No se utiliza un token de servicio, no se coloca el token en la URL y el
endpoint no es público.

## Cambios realizados en el backend

### Proxy de Analítica

Se incorporaron los siguientes componentes:

- `AnalyticsController`: expone `GET /admin/analitica`, realiza la autorización
  de panel y construye la respuesta para el frontend.
- `AnalyticsClient`: ejecuta la llamada HTTPS al servicio de Analítica con
  timeouts explícitos y reenvía el Bearer humano.
- `AnalyticsProxyResponse`: transporta el estado, las cabeceras y el cuerpo en
  bytes recibido desde el upstream.
- `AnalyticsConfig` y `AnalyticsProperties`: cargan y validan la configuración
  externa.

El cuerpo HTML se maneja como `byte[]`. El backend no lo parsea, no lo modifica
y no lo registra en logs.

### Autenticación y autorización

El endpoint exige un JWT humano válido y verifica el contrato de seguridad del
panel:

- audience administrativa válida;
- `token_use=human`;
- versión de contrato admitida;
- pertenencia al grupo `admin-global`.

Los delegados de módulo y el resto de las cuentas reciben `403` antes de que se
realice una llamada a Analítica. Un token ausente, vencido o inválido recibe
`401`.

### Reenvío del token

El JWT recibido mediante `@AuthenticationPrincipal Jwt` se reenvía exactamente
como:

```http
Authorization: Bearer <access-token>
Accept: text/html
```

No se generan credenciales adicionales y no se registran access tokens,
refresh tokens ni el contenido completo del dashboard.

### Cabeceras HTTP

El proxy conserva exclusivamente las siguientes cabeceras entregadas por
Analítica:

- `Content-Type`;
- `Cache-Control`;
- `Content-Security-Policy`;
- `X-Content-Type-Options`;
- `Referrer-Policy`.

Si Analítica no entrega `Cache-Control`, el backend agrega
`Cache-Control: no-store`. La cabecera `Set-Cookie` no se propaga.

Se creó una cadena de seguridad exclusiva para `/admin/analitica` que no agrega
`X-Frame-Options: DENY` ni reemplaza la CSP del HTML. Esta excepción está
limitada a esa ruta; el resto del backend conserva su CSP y
`X-Frame-Options: DENY`.

### Validación de la respuesta upstream

Para respuestas exitosas `2xx` se valida defensivamente que:

- el `Content-Type` sea HTML compatible con UTF-8;
- el cuerpo no esté vacío ni contenga únicamente espacios;
- el HTML permanezca sin parsear ni transformar.

Una respuesta exitosa que contenga JSON, texto vacío o un tipo incompatible se
traduce a `502 Bad Gateway`. Sólo se registra el estado y el `Content-Type`
inesperado.

Las respuestas no exitosas de Analítica, incluidos `401` y `403`, conservan su
estado y cuerpo. Los timeouts, errores DNS, conexiones rechazadas y demás
fallos de comunicación se traducen a `503 Service Unavailable`.

## Configuración

Se agregaron y documentaron las siguientes variables en `application.yml`,
`.env.example`, `docker-compose.yml` y `docker-compose.prod.yml`:

```dotenv
ANALYTICS_BASE_URL=https://citypass-backend-prod-becqetepcjbqa8c7.brazilsouth-01.azurewebsites.net
ANALYTICS_EMBED_PATH=/embed/identidad
ANALYTICS_CONNECT_TIMEOUT=3s
ANALYTICS_READ_TIMEOUT=15s
```

La configuración rechaza:

- URLs que no utilicen HTTPS;
- URLs con usuario, query string o fragmento;
- rutas relativas o URLs completas en `embed-path`;
- rutas con query string, fragmentos, barras invertidas o segmentos inseguros;
- timeouts nulos o no positivos, que son reemplazados por valores seguros.

La URL base se normaliza para evitar dobles barras al combinarla con la ruta.

## Consideración sobre tokens locales

Durante el desarrollo, un JWT emitido por Login Federado local puede ser
aceptado por el proxy local y luego rechazado con `401` por Analítica
productiva. Esto es correcto: el token local utiliza otro issuer y otras claves
RSA.

No se desactivó la validación de firma, no se copiaron claves privadas
productivas y no se reemplazó el token humano por un token de servicio.

Las alternativas válidas para pruebas son:

- utilizar un ambiente de Analítica que confíe en el JWKS local;
- probar el frontend local contra el backend productivo;
- utilizar un token emitido por el IdP productivo.

## Investigación de la falta de actualización

Cuando el dashboard permanecía en `18`, se verificó en producción que:

- `login_attempts` contenía los nuevos intentos con `successful=true`;
- se generaban eventos `identidad.login`, `identidad.refresh` e
  `identidad.logout`;
- los eventos del outbox quedaban en estado `SENT` y sin error.

Estas evidencias permitieron descartar al frontend, al proxy y al registro de
autenticaciones de Login Federado como origen del valor congelado. El dashboard
comenzó a actualizarse durante la noche del 18 de noviembre, luego de una
corrección del lado de Analítica cuya causa técnica no fue detallada.

## Pruebas y validaciones

Se agregaron pruebas automatizadas para cubrir:

- reenvío exacto del Bearer humano;
- envío de `Accept: text/html`;
- uso de `/embed/identidad`;
- preservación del HTML y de caracteres UTF-8;
- propagación de estado, cuerpo y cabeceras;
- exclusión de `Set-Cookie`;
- incorporación de `Cache-Control: no-store`;
- propagación de errores `401` y `403` del upstream;
- conversión de fallos de red y timeouts a `503`;
- respuestas exitosas no HTML o vacías convertidas a `502`;
- autorización exclusiva de `admin-global`;
- ausencia de `X-Frame-Options` sólo en `/admin/analitica`;
- validación y carga de propiedades de Spring;
- arranque del contexto con la configuración de Analítica.

También se validaron compilación, Checkstyle, suite unitaria, pruebas de
integración, Docker Compose y construcción de la imagen Docker.

## Resultado final

Al cierre de la integración:

- el frontend muestra el HTML definitivo mediante `/admin/analitica`;
- el frontend no llama directamente al servicio externo;
- el acceso está restringido a `admin-global`;
- se utiliza un único access token humano;
- el proxy no modifica ni cachea el contenido del dashboard;
- las autenticaciones y eventos se registran correctamente en producción;
- los indicadores del dashboard comenzaron a actualizarse el 18 de noviembre
  por la noche, después de una corrección del lado de Analítica.

## Archivos principales relacionados

- `src/main/java/citypass/loginfederado/analytics/AnalyticsController.java`
- `src/main/java/citypass/loginfederado/analytics/AnalyticsClient.java`
- `src/main/java/citypass/loginfederado/analytics/AnalyticsProxyResponse.java`
- `src/main/java/citypass/loginfederado/config/AnalyticsConfig.java`
- `src/main/java/citypass/loginfederado/config/AnalyticsProperties.java`
- `src/main/java/citypass/loginfederado/config/SecurityConfig.java`
- `src/main/java/citypass/loginfederado/exception/GlobalExceptionHandler.java`
- `src/main/resources/application.yml`
- `docs/runbooks/analytics-dashboard-deploy.md`

