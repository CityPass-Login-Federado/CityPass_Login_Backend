# ADR-001: LDAP como fuente de identidades

## Estado: Aceptado

## Contexto

CityPass+ necesita un sistema centralizado de identidades que sea compartido por los 7 módulos de la plataforma. Los usuarios son ciudadanos y administradores municipales. Se requiere:

- Autenticación centralizada (un solo login para toda la plataforma)
- Soporte para roles/grupos (ciudadano, admin, etc.)
- Independencia del módulo de aplicación (las credenciales no deben vivir en una BD de aplicación)

## Opciones consideradas

### Opción A: Base de datos relacional local (users table en PostgreSQL)

| Pros | Contras |
|------|---------|
| Simple de implementar | Cada módulo tendría su propia tabla de usuarios |
| No requiere infraestructura adicional | duplicación de datos y riesgo de inconsistencia |
| Integración nativa con Spring Data JPA | No es un directorio de identidades estándar |
| | Difícil de integrar con sistemas de identidad externos (SSO futuro) |

### Opción B: OpenLDAP (directorio de identidades)

| Pros | Contras |
|------|---------|
| Estándar de la industria para directorios de identidad | Requiere infraestructura adicional (servidor LDAP) |
| Un solo punto de verdad para todas las identidades | Curva de aprendizaje mayor que una tabla SQL |
| Soporte nativo en Spring Security LDAP | LDIF para seed data es menos intuitivo que SQL |
| Fácil de integrar con otros sistemas (SSO, SAML) | Gestión de contraseñas más compleja |
| Escalable horizontalmente (réplicas LDAP) | |
| Separación clara: identidad vs aplicación | |

### Opción C: OAuth2/OIDC externo (Auth0, Keycloak)

| Pros | Contras |
|------|---------|
| Solución managed, mínimo esfuerzo operativo | Dependencia de un proveedor externo |
| Soporte SSO, MFA out-of-the-box | Costo por usuario activo |
| Estándar OIDC | Complejidad de configuración para un proyecto académico |
| | Menos control sobre la infraestructura de identidad |

## Decisión

**Opción B: OpenLDAP**

Elegimos LDAP porque:
1. Es un estándar de la industria para directorios de identidad
2. Nos da un solo punto de verdad compartido por todos los módulos
3. Spring Security LDAP tiene soporte nativo (BindAuthenticator)
4. Es adecuado para un proyecto académico con infraestructura controlada
5. Nos prepara para SSO futuro sin cambiar la arquitectura

## Consecuencias

- Necesitamos un contenedor OpenLDAP en el docker-compose
- Los seed data se manejan en formato LDIF (no SQL)
- La autenticación es "bind authentication" (nunca comparamos contraseñas en la app)
- Los otros módulos NO tocan LDAP directamente — solo reciben JWTs validados
