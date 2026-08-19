# ADR-005: API stateless sin sesiones de servidor

## Estado: Aceptado

## Contexto

CityPass+ es una plataforma de microservicios con múltiples módulos. Cada módulo puede escalar horizontalmente (múltiples instancias detrás de un load balancer). Se necesita decidir cómo manejar el estado de autenticación entre requests.

## Opciones consideradas

### Opción A: Sesiones de servidor (HTTP sessions)

| Pros | Contras |
|------|---------|
| Simplicidad conceptual | Estado en servidor: dificulta escalar horizontalmente |
| Revocación instantánea (destruir sesión) | Requiere sesión distribuida (Redis) para múltiples instancias |
| Cookies son automáticas en browsers | No funciona bien con apps móviles nativas |
| | Acopla el cliente al servidor (cookie domain) |

### Opción B: Stateless con JWT

| Pros | Contras |
|------|---------|
| Escalable horizontalmente sin sesiones distribuidas | No se puede revocar access token sin blacklist |
| Funciona con cualquier cliente (web, móvil, IoT) | Token viaja en cada request (más overhead de red) |
| Cada módulo valida localmente | Rotación de refresh tokens necesaria para seguridad |
| Sin estado en servidor para auth | |

## Decisión

**Opción B: Stateless con JWT**

Elegimos stateless porque:
1. **Microservicios**: Cada módulo escala independientemente sin compartir estado de sesión
2. **Multi-plataforma**: Funciona con web, móvil, CLI, IoT — sin depender de cookies
3. **JWKS**: Los otros módulos validan tokens offline, sin llamadas al servicio de auth
4. **Simplicidad operativa**: No necesitamos Redis/sesiones distribuidas solo para auth

## Consecuencias

- El access token NO se puede revocar antes de expirar (15 min máximo)
- El refresh token opaco SÍ se puede revocar (en la BD)
- Logout = revocar todos los refresh tokens (el access token expira solo)
- CSRF se deshabilita (no hay cookies de sesión)
- Cada request incluye `Authorization: Bearer <token>`
