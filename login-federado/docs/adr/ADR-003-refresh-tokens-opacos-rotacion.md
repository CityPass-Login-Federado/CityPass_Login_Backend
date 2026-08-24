# ADR-003: Refresh tokens opacos con rotación y hash SHA-256

## Quiénes

| Nombre | Rol |
|--------|-----|
| Antonio Wu | Seguridad / Backend |

## Consideraciones

Los access tokens JWT tienen vida corta (15 min) para seguridad. Se necesita un mecanismo de refresh que permita al usuario obtener nuevos access tokens sin re-autenticarse. El refresh token tiene vida larga (7 días) y debe poder ser revocado.

Restricciones y supuestos adicionales:
- El refresh token es un credencial de alto valor: su fuga habilita sesiones indefinidas
- Debe soportar logout individual y masivo (revocar todas las sesiones del usuario)
- La detección de robo de tokens es deseable, no opcional

### Opciones consideradas

#### Opción A: JWT como refresh token (sin refresh token dedicado)

| Pros | Contras |
|------|---------|
| Un solo tipo de token | No se puede revocar sin blacklist (Redis/BD) |
| Simplifica la arquitectura de tokens | Si se compromete, vale por 7 días |
| | Requiere lookup en blacklist en cada refresh |

#### Opción B: Refresh token opaco + rotación de uso único

| Pros | Contras |
|------|---------|
| Revocación instantánea (flag en BD) | Requiere base de datos para cada refresh |
| Rotación: cada uso genera uno nuevo | Más complejo que un JWT |
| Detección de robo: reutilización = fraude | |
| Solo se almacena hash SHA-256 (nunca el crudo) | |
| Token de alta entropía (64 bytes random) | |

#### Opción C: Refresh token opaco + sin rotación

| Pros | Contras |
|------|---------|
| Más simple que con rotación | Token reutilizable: si se roba, se puede usar indefinidamente |
| | No detecta robo de tokens |

## Por todo esto, definimos

Adoptar **refresh tokens opacos de alta entropía (64 bytes aleatorios), con rotación de uso único y almacenamiento exclusivo del hash SHA-256**.

Razones principales:
1. **Rotación de uso único**: Cada refresh genera un token nuevo y revoca el viejo. Si alguien reutiliza un token ya usado, se detecta como intento de fraude
2. **SHA-256 hash**: Nunca almacenamos el token crudo — solo su hash. Si la BD se compromete, los tokens no se pueden reutilizar
3. **Revocación instantánea**: Un simple `UPDATE SET revoked=true` invalida la sesión
4. **Logout masivo**: Se revocan todos los tokens activos del usuario

Esta práctica coincide con la recomendación de la IETF en RFC 9700 (rotar refresh tokens tras cada uso).

## Consecuencias

### Positivas

- Revocación instantánea por sesión o masiva (logout global)
- Robo detectable: la reutilización de un token ya rotado dispara invalidación
- Fuga de la BD no compromete credenciales activas (solo hay hashes)
- Cumplimiento con OAuth 2.0 Security Best Current Practice

### Negativas

- Cada refresh genera 2 escrituras en BD (revocar viejo + insertar nuevo)
- El token crudo se muestra UNA sola vez al cliente: el cliente debe persistirlo de forma segura
- Complejidad adicional frente a un JWT de larga vida sin estado
- Expiración absoluta a los 7 días: obliga re-login aunque haya uso continuo

## Referencias (benchmark)

- RFC 6749 — The OAuth 2.0 Authorization Framework (§6 Refreshing an Access Token, §10.4) — https://datatracker.ietf.org/doc/html/rfc6749
- RFC 9700 — Best Current Practice for OAuth 2.0 Security (recomienda rotación de refresh tokens) — https://datatracker.ietf.org/doc/html/rfc9700
- RFC 6819 — OAuth 2.0 Threat Model and Security Considerations — https://datatracker.ietf.org/doc/html/rfc6819
- OWASP — Session Management Cheat Sheet (Refresh Tokens) — https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
- Auth0 — Refresh Token Rotation — https://auth0.com/docs/secure/tokens/refresh-tokens/refresh-token-rotation
