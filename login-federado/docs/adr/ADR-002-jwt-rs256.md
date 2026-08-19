# ADR-002: JWT RS256 como mecanismo de tokens de acceso

## Estado: Aceptado

## Contexto

El módulo de login necesita emitir tokens de acceso que los otros 6 módulos puedan validar sin llamar al servicio de autenticación en cada request. El token debe contener información del usuario (roles, nombre, email) y tener un tiempo de vida corto.

## Opciones consideradas

### Opción A: JWT HS256 (firma simétrica)

| Pros | Contras |
|------|---------|
| Más rápido de firmar y validar | La misma clave se usa para firmar y validar |
| Un solo secreto compartido | Si un módulo se compromete, puede firmar tokens falsos |
| Simple de configurar | No es escalable: todos los módulos necesitan la clave secreta |

### Opción B: JWT RS256 (firma asimétrica, RSA)

| Pros | Contras |
|------|---------|
| Solo la clave privada firma (segura) | Más lento que HS256 (operación RSA) |
| Cualquiera puede validar con la clave pública | Requiere gestión de par de claves |
| Los módulos no necesitan secretos — solo la pública | |
| Estándar para JWKS/.well-known | |
| Si un módulo se compromete, NO puede firmar tokens | |

### Opción C: Tokens opacos (session ID en servidor)

| Pros | Contras |
|------|---------|
| Simple, revocación instantánea | Requiere sesión de servidor (no stateless) |
| | No escala horizontalmente sin sesión distribuida (Redis) |
| | Cada request requiere lookup en BD/Redis |
| | Acopla todos los módulos al servicio de auth |

## Decisión

**Opción B: JWT RS256**

Elegimos RS256 porque:
1. **Seguridad**: La clave privada NUNCA sale del módulo de auth. Los otros módulos solo tienen la pública
2. **Stateless**: Cada módulo valida JWTs localmente sin llamadas de red
3. **JWKS**: El endpoint `/.well-known/jwks.json` es un estándar que cualquier librería JWT consume
4. **Escalabilidad**: Agregar un módulo nuevo solo requiere descargar la clave pública

## Consecuencias

- Las claves RSA se generan automáticamente en desarrollo (convenience)
- En producción, las claves deben administrarse via secrets del cloud provider
- El par de claves se rota periódicamente (key rotation) — JWKS soporta múltiples `kid`
- Los JWTs tienen `exp` corto (15 min) para limitar el impacto de tokens robados
