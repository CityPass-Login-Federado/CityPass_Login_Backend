# Despliegue del proxy del tablero de Analítica

Este runbook despliega el backend de Login Federado que expone
`GET /admin/analitica`. No modifica ni despliega el frontend ni el servicio de
Analítica. El upstream `/embed/identidad` ya entrega el HTML autocontenido
definitivo; no devuelve solamente `{"status":"ok"}`.

## Artefacto y configuración

- Desplegar una imagen inmutable construida desde el commit aprobado que integra
  el proxy; fijar `LOGIN_IMAGE_TAG` al tag o digest de esa imagen, no a `latest`.
- Registrar antes del cambio el commit, tag/digest y `LOGIN_IMAGE_TAG` actualmente
  desplegados para poder volver exactamente a la versión anterior.
- Configurar explícitamente:

  ```dotenv
  ANALYTICS_BASE_URL=https://citypass-backend-prod-becqetepcjbqa8c7.brazilsouth-01.azurewebsites.net
  ANALYTICS_EMBED_PATH=/embed/identidad
  ANALYTICS_CONNECT_TIMEOUT=3s
  ANALYTICS_READ_TIMEOUT=15s
  ```

- Verificar que ningún secreto o variable heredada sobrescriba
  `ANALYTICS_EMBED_PATH` con una ruta anterior.
- No reemplazar, regenerar ni copiar las claves RSA productivas. Conservar el
  volumen persistente `keysdata`; no ejecutar `docker compose down -v`.
- Mantener sin cambios el `JWT_ISSUER` productivo y el JWKS publicado en
  `/.well-known/jwks.json`. Antes y después del release, comprobar que el `kid`
  productivo esperado continúa presente.

## Despliegue y verificación

1. Confirmar que compilación, checkstyle, tests unitarios, integración, validación
   de Compose e imagen Docker estén aprobados para el commit a desplegar.
2. Publicar la imagen desde ese commit y actualizar solamente `LOGIN_IMAGE_TAG`
   (o el digest) y las cuatro variables `ANALYTICS_*`.
3. Reiniciar el servicio sin eliminar volúmenes y esperar que quede healthy.
4. Verificar `GET https://api.citypass-login.pp.ua/actuator/health` y
   `GET https://api.citypass-login.pp.ua/health`.
5. Confirmar que el JWKS y su `kid` no cambiaron.

## Smoke test autenticado

No imprimir ni guardar credenciales, access tokens o refresh tokens en archivos,
historial de shell, logs o evidencias. Usar un cliente que mantenga los valores en
memoria y enmascare headers sensibles.

1. Autenticarse con `POST https://api.citypass-login.pp.ua/auth/login` usando
   `clientId: citypass-admin-web` y una cuenta productiva `admin-global`.
2. Ejecutar `GET https://api.citypass-login.pp.ua/admin/analitica` con el access
   token devuelto en `Authorization: Bearer <access_token>` y `Accept: text/html`.
3. Confirmar estado 200, `Content-Type: text/html; charset=utf-8`, HTML completo,
   presencia de `citypass-datos`, `Cache-Control: no-store` o más restrictivo,
   CSP de Analítica, `X-Content-Type-Options: nosniff`,
   `Referrer-Policy: no-referrer` y ausencia de `X-Frame-Options: DENY`.
4. Confirmar además: sin token devuelve 401; un humano que no sea admin global
   devuelve 403 antes de llamar a Analítica; un admin global productivo devuelve
   200; y los demás endpoints conservan CSP y `X-Frame-Options: DENY`.

## Rollback

1. Restaurar el tag o digest de la imagen y el `LOGIN_IMAGE_TAG` registrados antes
   del despliegue.
2. Mantener intactos `keysdata`, las claves RSA, `JWT_ISSUER`, JWKS, LDAP y base de
   datos; el rollback no requiere migraciones destructivas.
3. Reiniciar el servicio, comprobar ambos healthchecks, el `kid` del JWKS y las
   cabeceras de seguridad de un endpoint existente.
