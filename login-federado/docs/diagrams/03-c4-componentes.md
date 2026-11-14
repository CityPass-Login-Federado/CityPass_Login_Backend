# C4 Component Diagram — Login Federado (Nivel 3)

```mermaid
C4Component
    title Componentes reales de login-federado

    Container_Boundary(login, "Spring Boot") {
        Component(authCtrl, "AuthController", "REST", "/auth/login, /refresh, /logout")
        Component(oauthCtrl, "OAuthTokenController", "REST", "client_credentials")
        Component(jwksCtrl, "JwksController", "REST", "JWKS público")
        Component(panelCtrl, "PanelController", "REST", "Personas y grupos")
        Component(authSvc, "AuthService", "Service", "Orquesta el login")
        Component(refreshSvc, "RefreshTokenService", "Service", "Rotación y detección de reuso")
        Component(attemptSvc, "LoginAttemptService", "Service", "Ventana de lockout")
        Component(directory, "LdapDirectory", "Adapter", "Búsqueda y bind")
        Component(panelSvc, "PanelDirectoryService", "Service", "Mutaciones LDAP")
        Component(audit, "PanelAuditService", "Service", "Auditoría SQL")
        Component(risk, "AnomalyRiskClient", "REST Client", "Scoring fail-closed")
        Component(tokens, "AccessTokenIssuer", "Component", "JWT RS256")
        Component(events, "LoggingEventPublisher", "Adapter", "Evento a log; sin broker")
    }

    System_Ext(ldap, "OpenLDAP", "Directorio")
    System_Ext(db, "PostgreSQL", "Persistencia operacional")
    System_Ext(ml, "anomaly-detection", "FastAPI")

    Rel(authCtrl, authSvc, "Delega")
    Rel(authSvc, directory, "Busca y autentica")
    Rel(authSvc, attemptSvc, "Verifica y registra")
    Rel(authSvc, risk, "Evalúa riesgo")
    Rel(authSvc, tokens, "Emite access token")
    Rel(authSvc, refreshSvc, "Emite refresh")
    Rel(authSvc, events, "Registra usuario.autenticado")
    Rel(panelCtrl, panelSvc, "Administra")
    Rel(panelSvc, ldap, "Escribe")
    Rel(panelSvc, audit, "Registra")
    Rel(directory, ldap, "LDAP")
    Rel(refreshSvc, db, "refresh_tokens")
    Rel(attemptSvc, db, "login_attempts")
    Rel(audit, db, "panel_audit")
    Rel(risk, ml, "POST /score")
```

## Paquetes reales

```text
citypass.loginfederado
├── controller/     Auth, JWKS y OAuth
├── service/        login, refresh y lockout
├── identity/       LDAP y registro de clientes
├── security/       cliente de riesgo
├── panel/          autorización, directorio y auditoría
├── token/          emisión JWT
├── event/          puerto y adaptador de logging
├── repository/     Spring Data JPA
├── config/         Security, LDAP, RSA y properties
└── exception/      errores REST centralizados
```
