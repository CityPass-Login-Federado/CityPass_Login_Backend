# ADR-001: LDAP como fuente de identidades

## Quiénes

| Nombre | Rol |
|--------|-----|
| Antonio Wu | Seguridad / Backend |

## Consideraciones

CityPass+ necesita un sistema centralizado de identidades que sea compartido por los 7 módulos de la plataforma. Los usuarios son ciudadanos y administradores municipales. Se requiere:

- Autenticación centralizada (un solo login para toda la plataforma)
- Soporte para roles/grupos (ciudadano, admin, etc.)
- Independencia del módulo de aplicación (las credenciales no deben vivir en una BD de aplicación)

Restricciones y supuestos adicionales:
- Infraestructura controlada por el equipo (docker-compose propio, proyecto académico)
- Sin presupuesto para servicios managed externos
- El resto de los módulos consume identidad exclusivamente vía tokens emitidos por este módulo

### Opciones consideradas

#### Opción A: Base de datos relacional local (users table en PostgreSQL)

| Pros | Contras |
|------|---------|
| Simple de implementar | Cada módulo tendría su propia tabla de usuarios |
| No requiere infraestructura adicional | Duplicación de datos y riesgo de inconsistencia |
| Integración nativa con Spring Data JPA | No es un directorio de identidades estándar |
| | Difícil de integrar con sistemas de identidad externos (SSO futuro) |

#### Opción B: OpenLDAP (directorio de identidades)

| Pros | Contras |
|------|---------|
| Estándar de la industria para directorios de identidad | Requiere infraestructura adicional (servidor LDAP) |
| Un solo punto de verdad para todas las identidades | Curva de aprendizaje mayor que una tabla SQL |
| Soporte nativo en Spring Security LDAP | LDIF para seed data es menos intuitivo que SQL |
| Fácil de integrar con otros sistemas (SSO, SAML) | Gestión de contraseñas más compleja |
| Escalable horizontalmente (réplicas LDAP) | |
| Separación clara: identidad vs aplicación | |

#### Opción C: OAuth2/OIDC externo (Auth0, Keycloak)

| Pros | Contras |
|------|---------|
| Solución managed, mínimo esfuerzo operativo | Dependencia de un proveedor externo |
| Soporte SSO, MFA out-of-the-box | Costo por usuario activo |
| Estándar OIDC | Complejidad de configuración para un proyecto académico |
| | Menos control sobre la infraestructura de identidad |

## Por todo esto, definimos

Adoptar **OpenLDAP como directorio central de identidades** (Opción B), con autenticación por bind contra Spring Security LDAP.

Razones principales:
1. Es un estándar de la industria para directorios de identidad
2. Nos da un solo punto de verdad compartido por todos los módulos
3. Spring Security LDAP tiene soporte nativo (BindAuthenticator)
4. Es adecuado para un proyecto académico con infraestructura controlada
5. Nos prepara para SSO futuro sin cambiar la arquitectura

## Consecuencias

### Positivas

- Identidad separada de las aplicaciones: las credenciales no viven en ninguna BD de módulo
- Un solo login válido para toda la plataforma CityPass+
- La autenticación es bind authentication: la app nunca compara contraseñas directamente
- Base sólida para SSO futuro (SAML/OIDC) sin rediseño

### Negativas

- Necesitamos un contenedor OpenLDAP en el docker-compose
- Los seed data se manejan en formato LDIF (no SQL), menos intuitivo para el equipo
- Gestión de contraseñas y esquema de directorio más complejos que una tabla SQL
- Curva de aprendizaje inicial del modelo jerárquico DN/OU

## Referencias (benchmark)

- RFC 4511 — Lightweight Directory Access Protocol (LDAP): The Protocol — https://datatracker.ietf.org/doc/html/rfc4511
- RFC 4513 — LDAP: Authentication Methods and Security Mechanisms — https://datatracker.ietf.org/doc/html/rfc4513
- OpenLDAP Software 2.5 Administrator's Guide — https://www.openldap.org/doc/admin25/
- Spring Security — LDAP Authentication — https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/ldap.html
