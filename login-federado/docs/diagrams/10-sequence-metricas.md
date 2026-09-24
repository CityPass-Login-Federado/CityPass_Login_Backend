# Secuencia — Publicación de eventos de identidad para EDA y métricas

**Fecha de revisión:** 24 de septiembre de 2026

El backend produce eventos crudos de autenticación. No calcula actualmente DAU, MAU ni agregados diarios; esas métricas corresponden a consumidores posteriores del flujo EDA.

```mermaid
sequenceDiagram
    autonumber
    participant AS as AuthService
    participant EP as EventPublisher
    participant LP as LoggingEventPublisher
    participant HP as EdaHttpEventPublisher
    participant TC as Caché de token de servicio
    participant OAuth as Endpoint OAuth configurado
    participant GW as CityPass Event Gateway

    AS->>EP: publish(RawAuthenticationEvent)
    Note over AS,EP: Tipos: identidad.login,<br/>identidad.refresh e identidad.logout
    alt Publicador predeterminado logging
        EP->>LP: Serializar y registrar evento
        LP-->>AS: Operación completada
    else Publicador HTTP habilitado
        EP->>HP: Publicar evento
        HP->>TC: Obtener token grupo2
        alt No hay token o vence en menos de 30 s
            HP->>OAuth: POST /oauth/token<br/>grant_type=client_credentials
            OAuth-->>HP: Access token de servicio
            HP->>TC: Guardar token y expiración
        end
        HP->>HP: Construir envelope con metadata,<br/>source, namespace, tokenId y actorSub
        HP->>GW: POST evento con Bearer token
        GW-->>HP: Resultado HTTP
        HP-->>AS: Operación completada o error
    end
```

## Estado actual

- `LoggingEventPublisher` es el modo predeterminado y deja evidencia local del evento.
- `EdaHttpEventPublisher` es opcional y requiere configuración explícita del gateway y del endpoint OAuth.
- La entrega HTTP es sincrónica y no existe una bandeja transaccional (*outbox*); un fallo puede propagarse después de que la autenticación haya modificado estado.
- El contrato incluye datos de identidad mínimos para que otro módulo realice métricas, auditoría o detección de patrones, sin incorporar un planificador diario en este backend.

