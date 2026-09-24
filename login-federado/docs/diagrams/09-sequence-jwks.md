# Secuencia — Validación de JWT mediante JWKS

**Fecha de revisión:** 24 de septiembre de 2026
**Endpoint público:** `GET /.well-known/jwks.json`

Los módulos consumidores pueden validar localmente los access tokens sin consultar al módulo de Login en cada solicitud.

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente autenticado
    participant API as API de un módulo CityPass+
    participant JWKS as Endpoint JWKS de Login
    participant K as Caché local de claves

    C->>API: Solicitud con Authorization: Bearer JWT
    API->>API: Leer encabezado alg y kid
    alt Clave kid ausente o caché vencida
        API->>JWKS: GET /.well-known/jwks.json
        JWKS-->>API: 200 OK con claves públicas RSA
        API->>K: Actualizar caché
    end
    API->>K: Obtener clave pública por kid
    K-->>API: Clave pública
    API->>API: Verificar firma RS256, exp, aud,<br/>token_use y claims de autorización
    alt Token válido para este recurso
        API-->>C: Respuesta del recurso
    else Token inválido o audiencia incorrecta
        API-->>C: 401 o 403
    end
```

## Responsabilidades del consumidor

- Aceptar únicamente algoritmos y claves esperados; no confiar en el algoritmo declarado sin una política local.
- Validar expiración, audiencia y `token_use`, además de los claims funcionales necesarios.
- Renovar la caché cuando aparece un `kid` desconocido para admitir rotación de claves.
- Aplicar autorización propia: publicar JWKS resuelve la verificación criptográfica, no las reglas de negocio del módulo consumidor.
