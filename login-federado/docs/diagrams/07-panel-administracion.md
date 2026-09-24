# Secuencias — Panel de administración

**Fecha de revisión:** 24 de septiembre de 2026
**Base de rutas:** `/panel`

El panel exige un JWT humano emitido para la audiencia `admin`. La autorización diferencia a los delegados de módulo de los administradores globales.

## Autorización y consultas

```mermaid
sequenceDiagram
    autonumber
    actor A as Administrador
    participant UI as Panel web
    participant SC as SecurityFilterChain
    participant PA as PanelAuthorization
    participant PC as PanelController
    participant PS as PanelService
    participant LDAP as LDAP corporativo

    A->>UI: Abre el panel
    UI->>SC: Solicitud con Bearer JWT
    SC->>SC: Verificar firma RS256, exp,<br/>token_use=access y aud=admin
    alt Token inválido
        SC-->>UI: 401 Unauthorized
    else Token válido
        SC->>PA: Evaluar groups y module
        alt No pertenece a delegados ni admin-global
            PA-->>UI: 403 Forbidden
        else Es delegado
            PA->>PC: Autorizar sólo el módulo del claim
            PC->>PS: Consultar personas o grupos del módulo
            PS->>LDAP: Buscar entradas y membresías
            LDAP-->>PS: Resultado
            PS-->>UI: 200 OK
        else Es admin-global
            alt Operación modular sin parámetro module
                PA-->>UI: 400 Bad Request
            else Módulo explícito o consulta global
                PA->>PC: Autorizar alcance solicitado
                PC->>PS: Ejecutar consulta
                PS->>LDAP: Buscar entradas y membresías
                LDAP-->>PS: Resultado
                PS-->>UI: 200 OK
            end
        end
    end
```

## Operaciones de escritura y auditoría

```mermaid
sequenceDiagram
    autonumber
    actor A as Administrador autorizado
    participant UI as Panel web
    participant PC as PanelController
    participant PS as PanelService
    participant LDAP as LDAP corporativo
    participant RT as RefreshTokenService
    participant DB as PostgreSQL
    participant AU as PanelAuditService

    A->>UI: Crea, modifica o elimina una entidad
    UI->>PC: Solicitud bajo /panel<br/>con módulo cuando corresponde
    PC->>PS: Ejecutar comando autorizado
    alt Personas
        PS->>LDAP: Crear, actualizar, habilitar,<br/>deshabilitar o eliminar persona
        opt Persona deshabilitada
            PS->>RT: Revocar sesiones de la persona
            RT->>DB: Revocar refresh tokens activos
        end
    else Grupos
        PS->>LDAP: Crear, actualizar o eliminar grupo
    else Membresías
        PS->>LDAP: Agregar, quitar o aplicar operación masiva
    end
    LDAP-->>PS: Resultado de la operación
    PS->>AU: Registrar acción, actor, objetivo y resultado
    AU->>DB: Insertar panel_audit
    alt Fallo al guardar auditoría
        Note over AU,DB: El error se registra,<br/>la operación LDAP no se revierte
    end
    PS-->>PC: Resultado
    PC-->>UI: 2xx o error de dominio
```

## Reglas vigentes

- Un delegado sólo opera sobre el módulo consignado en su token.
- `admin-global` puede operar transversalmente; las operaciones modulares requieren `?module=` y existen rutas de consulta global.
- Los módulos reconocidos son `movilidad`, `residuos`, `reclamos`, `emergencias`, `espacios`, `analitica` y `eda`.
- La implementación contempla excepciones administrativas para que `admin-global` pueda vaciar o eliminar el grupo `delegados`; por lo tanto, no debe documentarse como un invariante universal.
- La auditoría es de mejor esfuerzo y no conforma una transacción distribuida con LDAP.
