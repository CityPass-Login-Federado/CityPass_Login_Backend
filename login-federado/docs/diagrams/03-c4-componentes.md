# C4 Component Diagram — Login Federado (Nivel 3)

```mermaid
C4Component
    title Diagrama de Componentes — Módulo Login Federado

    Container_Boundary(login, "Login Federado (Spring Boot)") {
        Component(authCtrl, "AuthController", "REST Controller", "Endpoints /auth/login, /auth/refresh, /auth/logout")
        Component(jwksCtrl, "JwksController", "REST Controller", "Endpoint /.well-known/jwks.json")
        Component(authSvc, "AuthService", "Service", "Orquesta login, refresh, logout")
        Component(rtSvc, "RefreshTokenService", "Service", "Genera, valida y rota refresh tokens")
        Component(laSvc, "LoginAttemptService", "Service", "Bloqueo por fuerza bruta (ventana deslizante)")
        Component(evtPub, "EventPublisher", "Interface", "Publica eventos de autenticación")
        Component(secCfg, "SecurityConfig", "Config", "Cadena de filtros HTTP, endpoints públicos")
        Component(ldapCfg, "LdapConfig", "Config", "Autenticación LDAP bind, búsqueda de usuarios")
        Component(jwtCfg, "JwtKeyConfig", "Config", "Par de claves RSA, JwtEncoder/JwtDecoder")
    }

    System_Ext(openldap, "OpenLDAP", "Directorio de identidades")
    System_Ext(postgres, "PostgreSQL", "Base de datos")

    Rel(authCtrl, authSvc, "Delega autenticación")
    Rel(authSvc, ldapCfg, "Autentica contra LDAP")
    Rel(authSvc, jwtCfg, "Emite JWT")
    Rel(authSvc, rtSvc, "Emite refresh token")
    Rel(authSvc, laSvc, "Registra/verifica intentos")
    Rel(authSvc, evtPub, "Publica evento")
    Rel(rtSvc, postgres, "Persiste tokens (hash SHA-256)")
    Rel(laSvc, postgres, "Persiste intentos de login")
    Rel(jwksCtrl, jwtCfg, "Expone clave pública")
```

## Paquetes del proyecto

```
citypass.loginfederado
├── controller/     → AuthController, JwksController
├── service/        → AuthService, RefreshTokenService, LoginAttemptService
├── security/       → LdapUserPrincipal, CustomLdapUserDetailsMapper
├── config/         → SecurityConfig, LdapConfig, JwtKeyConfig, JwtProperties
├── repository/     → RefreshTokenRepository, LoginAttemptRepository
├── model/          → RefreshToken, LoginAttempt (JPA entities)
├── dto/            → LoginRequest, LoginResponse, RefreshRequest
├── event/          → EventPublisher, LoggingEventPublisher, UsuarioAutenticadoEvent
└── exception/      → AccountLockedException, GlobalExceptionHandler
```
