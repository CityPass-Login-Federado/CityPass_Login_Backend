# Secuencia — Publicación y consumo de JWKS

**Estado representado:** publicación AS-IS en Login Federado y contrato esperado para las APIs consumidoras.

```mermaid
sequenceDiagram
    autonumber
    participant API as API de módulo CityPass+
    participant SEC as SecurityFilterChain
    participant JC as JwksController
    participant RSA as RSAKey
    actor U as Cliente con access token

    API->>SEC: GET /.well-known/jwks.json
    SEC->>JC: Endpoint público
    JC->>RSA: toPublicJWK()
    RSA-->>JC: Clave pública + kid
    JC-->>API: 200 {keys:[JWK pública]}
    API->>API: Almacena JWKS en caché según su política

    U->>API: Request funcional + Bearer accessToken
    API->>API: Selecciona JWK por kid
    API->>API: Valida firma RS256 y expiración
    API->>API: Valida issuer, audience, token_use, ver y claims requeridos

    alt token inválido o claims incompatibles
        API-->>U: 401/403 según la API consumidora
    else token válido
        API-->>U: Respuesta funcional
    end
```

## Límites de responsabilidad

- Login Federado publica únicamente material público; la clave privada no sale del contenedor.
- La validación de cada request ocurre localmente en la API consumidora: no existe una llamada de introspección a Login Federado.
- Este repositorio implementa el endpoint JWKS. La política exacta de caché y validación de cada API debe verificarse en el repositorio de ese módulo.
- El `kid` se deriva de la huella RFC 7638 de la clave pública.
