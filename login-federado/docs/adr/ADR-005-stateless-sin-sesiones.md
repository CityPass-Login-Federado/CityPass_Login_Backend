# ADR-005: API stateless sin sesiones de servidor

## Quiénes

| Nombre | Rol |
|--------|-----|
| Antonio Wu | Seguridad / Backend |

## Consideraciones

CityPass+ es una plataforma de microservicios con múltiples módulos. Cada módulo puede escalar horizontalmente (múltiples instancias detrás de un load balancer). Se necesita decidir cómo manejar el estado de autenticación entre requests.

Restricciones y supuestos adicionales:
- Los clientes incluyen web, apps móviles nativas y futuros integradores externos (API pública)
- Ninguna instancia puede depender de estado en memoria local (cualquier request puede llegar a cualquier réplica)
- Se busca minimizar dependencias de infraestructura para auth

### Opciones consideradas

#### Opción A: Sesiones de servidor (HTTP sessions)

| Pros | Contras |
|------|---------|
| Simplicidad conceptual | Estado en servidor: dificulta escalar horizontalmente |
| Revocación instantánea (destruir sesión) | Requiere sesión distribuida (Redis) para múltiples instancias |
| Cookies son automáticas en browsers | No funciona bien con apps móviles nativas |
| | Acopla el cliente al servidor (cookie domain) |

#### Opción B: Stateless con JWT

| Pros | Contras |
|------|---------|
| Escalable horizontalmente sin sesiones distribuidas | No se puede revocar access token sin blacklist |
| Funciona con cualquier cliente (web, móvil, IoT) | Token viaja en cada request (más overhead de red) |
| Cada módulo valida localmente | Rotación de refresh tokens necesaria para seguridad |
| Sin estado en servidor para auth | |

## Por todo esto, definimos

Adoptar una **API completamente stateless**: autenticación vía header `Authorization: Bearer <JWT>`, sin HTTP sessions ni cookies de sesión, con CSRF deshabilitado.

Razones principales:
1. **Microservicios**: Cada módulo escala independientemente sin compartir estado de sesión
2. **Multi-plataforma**: Funciona con web, móvil, CLI, IoT — sin depender de cookies
3. **JWKS**: Los otros módulos validan tokens offline, sin llamadas al servicio de auth
4. **Simplicidad operativa**: No necesitamos Redis/sesiones distribuidas solo para auth

Este enfoque sigue el principio "stateless, share nothing" del manifiesto Twelve-Factor App (factor VI: Processes).

## Consecuencias

### Positivas

- Cualquier instancia atiende cualquier request: escalado horizontal trivial
- Compatibilidad total con clientes no-browser (móvil, IoT, integradores)
- Sin Redis ni sticky sessions dedicados a auth
- CSRF eliminado por diseño (no hay cookies de autenticación que secuestrar)

### Negativas

- El access token NO se puede revocar antes de expirar (ventana máxima: 15 min)
- Logout no invalida el access token activo: solo los refresh tokens (ADR-003)
- Overhead de red: el JWT completo viaja en cada request
- Obliga a mantener rotación de refresh tokens como compensación de seguridad

## Referencias (benchmark)

- RFC 7519 — JSON Web Token (JWT) — https://datatracker.ietf.org/doc/html/rfc7519
- The Twelve-Factor App — Factor VI: Processes (stateless, share nothing) — https://12factor.net/es/processes
- OWASP — Session Management Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
- Sam Newman — Building Microservices, 2nd Edition (O'Reilly) — capítulo de seguridad en microservicios
