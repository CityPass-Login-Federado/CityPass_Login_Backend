# UML - Casos de uso del módulo Login Federado

Versión corregida a partir del diagrama publicado en Notion y contrastada con los endpoints actuales. Mermaid no posee una notación UML de casos de uso estable en todos los renderizadores, por lo que se utiliza un `flowchart` con actores, límite de sistema y relaciones etiquetadas.

Fuente original: [Diagramas UML (CU, ER) - Módulo 2](https://app.notion.com/p/drff/Diagramas-UML-CU-ER-Modulo-2-3c977c7e30048071b13dd90ecd96a45b?source=copy_link)

## Autenticación e integración

```mermaid
flowchart LR
    PERSON[Persona usuaria]
    WEB[Aplicación web registrada]
    SERVICE[Servicio backend]
    API[API consumidora]
    ANALYTICS[Equipo de Analítica]

    subgraph IDP[Módulo 2 - Login Federado]
        LOGIN([Iniciar sesión])
        REFRESH([Renovar sesión])
        LOGOUT([Cerrar sesión])
        M2M([Obtener token de servicio])
        JWKS([Consultar claves públicas])
        METRICS([Publicar métricas diarias])
    end

    PERSON --> WEB
    WEB --> LOGIN
    WEB --> REFRESH
    WEB --> LOGOUT
    SERVICE --> M2M
    API --> JWKS
    METRICS -. evento mediante adaptador .-> ANALYTICS
```

Reglas relevantes:

- El login usa un cliente humano registrado y valida que su módulo acepte a la persona.
- Refresh rota el token, relee LDAP y revoca toda la cadena ante reúso.
- Logout revoca el refresh token presentado; el access token expira naturalmente.
- Client Credentials emite un token de servicio sin grupos, módulo ni refresh token.
- La publicación de eventos y métricas usa actualmente un adaptador de logging, no un broker real.

## Panel de administración

```mermaid
flowchart LR
    DELEGATE[Delegado de módulo]
    GLOBAL[Administrador global]

    subgraph PANEL[Panel de administración]
        LIST_PERSON([Listar y consultar personas])
        CREATE_PERSON([Crear persona])
        UPDATE_PERSON([Actualizar persona])
        DISABLE_PERSON([Deshabilitar persona])
        ENABLE_PERSON([Rehabilitar persona])
        RESET_PASSWORD([Restablecer contraseña])
        LIST_GROUP([Listar grupos])
        CREATE_GROUP([Crear grupo])
        DELETE_GROUP([Eliminar grupo])
        ADD_MEMBER([Agregar miembro])
        REMOVE_MEMBER([Quitar miembro])
        LIST_MODULE([Listar módulos])
        REVOKE_ALL([Revocar sesiones renovables])
        AUDIT([Registrar auditoría])
    end

    DELEGATE --> LIST_PERSON
    DELEGATE --> CREATE_PERSON
    DELEGATE --> UPDATE_PERSON
    DELEGATE --> DISABLE_PERSON
    DELEGATE --> ENABLE_PERSON
    DELEGATE --> RESET_PASSWORD
    DELEGATE --> LIST_GROUP
    DELEGATE --> CREATE_GROUP
    DELEGATE --> DELETE_GROUP
    DELEGATE --> ADD_MEMBER
    DELEGATE --> REMOVE_MEMBER

    GLOBAL --> LIST_MODULE
    GLOBAL --> LIST_PERSON
    GLOBAL --> CREATE_PERSON
    GLOBAL --> UPDATE_PERSON
    GLOBAL --> DISABLE_PERSON
    GLOBAL --> ENABLE_PERSON
    GLOBAL --> RESET_PASSWORD
    GLOBAL --> LIST_GROUP
    GLOBAL --> CREATE_GROUP
    GLOBAL --> DELETE_GROUP
    GLOBAL --> ADD_MEMBER
    GLOBAL --> REMOVE_MEMBER

    DISABLE_PERSON -. incluye .-> REVOKE_ALL
    CREATE_PERSON -. incluye .-> AUDIT
    UPDATE_PERSON -. incluye .-> AUDIT
    DISABLE_PERSON -. incluye .-> AUDIT
    ENABLE_PERSON -. incluye .-> AUDIT
    RESET_PASSWORD -. incluye .-> AUDIT
    CREATE_GROUP -. incluye .-> AUDIT
    DELETE_GROUP -. incluye .-> AUDIT
    ADD_MEMBER -. incluye .-> AUDIT
    REMOVE_MEMBER -. incluye .-> AUDIT
```

Reglas de autorización:

- Ambos actores necesitan un JWT humano con audiencia `citypass-admin-api` y versión de contrato `1`.
- El delegado opera exclusivamente el módulo de su claim `module`.
- El administrador global debe indicar un `?module=` válido en cada operación sobre personas o grupos.
- No existe el caso de uso "eliminar persona"; la baja se implementa mediante deshabilitación en LDAP.
- El grupo reservado `delegados` no se elimina y no puede quedar vacío.
