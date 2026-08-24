# ADR-002: JWT RS256 como mecanismo de tokens de acceso

## Quiénes

| Nombre | Rol |
|--------|-----|
| Antonio Wu | Seguridad / Backend |

## Consideraciones

El módulo de login necesita emitir tokens de acceso que los otros 6 módulos puedan validar sin llamar al servicio de autenticación en cada request. El token debe contener información del usuario (roles, nombre, email) y tener un tiempo de vida corto.

Restricciones y supuestos adicionales:
- La clave de firma nunca debe salir del módulo de autenticación
- Los módulos consumidores deben poder validar offline, sin llamadas de red
- Debe ser compatible con estándares abiertos para facilitar la adopción por otros equipos

### Opciones consideradas

#### Opción A: JWT HS256 (firma simétrica)

| Pros | Contras |
|------|---------|
| Más rápido de firmar y validar | La misma clave se usa para firmar y validar |
| Un solo secreto compartido | Si un módulo se compromete, puede firmar tokens falsos |
| Simple de configurar | No es escalable: todos los módulos necesitan la clave secreta |

#### Opción B: JWT RS256 (firma asimétrica, RSA)

| Pros | Contras |
|------|---------|
| Solo la clave privada firma (segura) | Más lento que HS256 (operación RSA) |
| Cualquiera puede validar con la clave pública | Requiere gestión de par de claves |
| Los módulos no necesitan secretos — solo la pública | |
| Estándar para JWKS/.well-known | |
| Si un módulo se compromete, NO puede firmar tokens | |

#### Opción C: Tokens opacos (session ID en servidor)

| Pros | Contras |
|------|---------|
| Simple, revocación instantánea | Requiere sesión de servidor (no stateless) |
| | No escala horizontalmente sin sesión distribuida (Redis) |
| | Cada request requiere lookup en BD/Redis |
| | Acopla todos los módulos al servicio de auth |

## Por todo esto, definimos

Emitir **tokens de acceso como JWT firmados con RS256** (firma asimétrica RSA), publicando las claves públicas vía JWKS (`/.well-known/jwks.json`).

Razones principales:
1. **Seguridad**: La clave privada NUNCA sale del módulo de auth. Los otros módulos solo tienen la pública
2. **Stateless**: Cada módulo valida JWTs localmente sin llamadas de red
3. **JWKS**: El endpoint `/.well-known/jwks.json` es un estándar que cualquier librería JWT consume
4. **Escalabilidad**: Agregar un módulo nuevo solo requiere descargar la clave pública

## Consecuencias

### Positivas

- Compromiso de un módulo consumidor NO permite forjar tokens: solo poseen la clave pública
- Validación local sin latencia de red ni acoplamiento al servicio de auth
- Rotación de claves soportada nativamente por JWKS (múltiples `kid`)
- Incorporar módulos nuevos es trivial (descargar clave pública)

### Negativas

- Firma/validación RSA más lenta que HS256
- Requiere gestionar el par de claves: generación, almacenamiento seguro y rotación periódica
- El access token no puede revocarse antes de expirar (mitigado con `exp` corto de 15 min + refresh opaco revocable)
- Tokens más largos que un session ID: mayor overhead en headers HTTP

## Referencias (benchmark)

- RFC 7519 — JSON Web Token (JWT) — https://datatracker.ietf.org/doc/html/rfc7519
- RFC 7515 — JSON Web Signature (JWS) — https://datatracker.ietf.org/doc/html/rfc7515
- RFC 7517 — JSON Web Key (JWK) — https://datatracker.ietf.org/doc/html/rfc7517
- RFC 8725 — Best Current Practices for Protecting JWTs — https://datatracker.ietf.org/doc/html/rfc8725
- OWASP — JSON Web Token Cheat Sheet for Java — https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html
- Auth0 — Signing Algorithms (RS256 vs HS256) — https://auth0.com/docs/tokens/reference/signing-algorithms
