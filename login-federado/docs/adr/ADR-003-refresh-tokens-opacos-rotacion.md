# ADR-003: Refresh tokens opacos con rotación y hash SHA-256

## Estado: Aceptado

## Contexto

Los access tokens JWT tienen vida corta (15 min) para seguridad. Se necesita un mecanismo de refresh que permita al usuario obtener nuevos access tokens sin re-autenticarse. El refresh token tiene vida larga (7 días) y debe poder ser revocado.

## Opciones consideradas

### Opción A: JWT como refresh token (sin refresh token dedicado)

| Pros | Contras |
|------|---------|
| Un solo tipo de token | No se puede revocar sin blacklist (Redis/BD) |
| Simplifica la arquitectura de tokens | Si se compromete, vale por 7 días |
| | Requiere lookup en blacklist en cada refresh |

### Opción B: Refresh token opaco + rotación de uso único

| Pros | Contras |
|------|---------|
| Revocación instantánea (flag en BD) | Requiere base de datos para cada refresh |
| Rotación: cada uso genera uno nuevo | Más complejo que un JWT |
| Detección de robo: reutilización = fraude | |
| Solo se almacena hash SHA-256 (nunca el crudo) | |
| Token de alta entropía (64 bytes random) | |

### Opción C: Refresh token opaco + sin rotación

| Pros | Contras |
|------|---------|
| Más simple que con rotación | Token reutilizable: si se roba, se puede usar indefinidamente |
| | No detecta robo de tokens |

## Decisión

**Opción B: Refresh token opaco con rotación de uso único + SHA-256**

Elegimos esta opción porque:
1. **Rotación de uso único**: Cada refresh genera un token nuevo y revoca el viejo. Si alguien reutiliza un token ya usado, se detecta como intento de fraude
2. **SHA-256 hash**: Nunca almacenamos el token crudo — solo su hash. Si la BD se compromete, los tokens no se pueden reutilizar
3. **Revocación instantánea**: Un simple `UPDATE SET revoked=true` invalida la sesión
4. **Logout masivo**: Se revocan todos los tokens activos del usuario

## Consecuencias

- Cada refresh genera 2 escrituras en BD (revocar viejo + insertar nuevo)
- El token crudo se muestra UNA sola vez al cliente
- El cliente debe persistir el refresh token de forma segura (secure storage)
- No hay refresh token "eterno" — expira después de 7 días independientemente de uso
