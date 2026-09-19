# Secuencia — Token de servicio con client_credentials

**Estado representado:** AS-IS del endpoint `POST /oauth/token`.

```mermaid
sequenceDiagram
    autonumber
    actor S as Servicio backend
    participant OC as OAuthTokenController
    participant CR as ClientRegistry
    participant ATI as AccessTokenIssuer
    participant JK as JwtKeyConfig / RSAKey

    S->>OC: POST /oauth/token con Basic Auth y grant_type=client_credentials

    OC->>OC: Verifica grant_type
    alt grant_type distinto de client_credentials
        OC-->>S: 401 genérico
    end

    OC->>OC: Decodifica Basic Auth
    alt cabecera ausente o inválida
        OC-->>S: 401 genérico
    end

    OC->>CR: authenticateService(clientId, clientSecret)
    CR->>CR: Verifica cliente service y compara el secret en tiempo constante
    alt cliente o secret inválidos
        CR-->>S: 401 genérico
    end

    OC->>ATI: issueService(client)
    ATI->>JK: Firma RS256 con kid RFC 7638
    Note over ATI: Claims de servicio: sub, aud, token_use, ver y namespace
    Note over ATI: Claims estándar: iss, iat, exp y jti. Sin groups ni module
    ATI-->>OC: JWT de servicio (60 min)
    OC-->>S: 200 {access_token, token_type=Bearer, expires_in=3600}
```

## Reglas reflejadas

1. El endpoint es público porque es el emisor del token; la autenticación se realiza mediante Basic Auth.
2. Solo admite clientes registrados con `type=service` y `grant_type=client_credentials`.
3. El token de servicio no representa a una persona y no contiene `groups` ni `module`.
4. No se emite refresh token para servicios.
