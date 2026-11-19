# Secuencia — OAuth 2.0 Client Credentials

**Fecha de revisión:** 19 de noviembre de 2026
**Endpoint:** `POST /oauth/token`

El flujo está destinado exclusivamente a integraciones máquina a máquina registradas como clientes de servicio.

```mermaid
sequenceDiagram
    autonumber
    participant S as Servicio consumidor
    participant OC as OAuthTokenController
    participant OA as OAuthClientAuthenticator
    participant CR as ClientRegistry
    participant JWT as ServiceTokenIssuer

    S->>OC: POST /oauth/token<br/>Content-Type: application/x-www-form-urlencoded<br/>grant_type=client_credentials
    Note over S,OC: Credenciales mediante HTTP Basic<br/>o client_id y client_secret en el formulario
    alt grant_type no soportado
        OC-->>S: 400 unsupported_grant_type
    else Faltan credenciales
        OC-->>S: 400 invalid_request
    else Solicitud completa
        OC->>OA: authenticate(clientId, clientSecret)
        OA->>CR: Buscar cliente de servicio
        alt Cliente desconocido, humano o secreto inválido
            OA-->>OC: Credenciales inválidas
            OC-->>S: 401 invalid_client
        else Cliente de servicio válido
            OA-->>OC: Cliente y audiencia
            OC->>JWT: Emitir JWT RS256 de servicio
            Note over JWT: sub=clientId, aud configurada,<br/>token_use=service y ver<br/>Sin groups, module ni refresh token
            JWT-->>OC: Access token con vigencia de 15 min
            OC-->>S: 200 OK<br/>token_type=Bearer, expires_in=900
        end
    end
```

Los clientes de servicio configurados actualmente incluyen los clientes oficiales `grupo1` a `grupo8` y los alias legacy `group1` y `group5`. La respuesta de error sigue el formato OAuth y no el contrato de autenticación humana.
