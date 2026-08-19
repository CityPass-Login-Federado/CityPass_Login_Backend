# ADR-006: Event bus — placeholder con abstracción para Kafka/RabbitMQ

## Estado: Aceptado

## Contexto

CityPass+ requiere comunicación asincrónica entre módulos (EDA — Event Driven Architecture). El Grupo 1 diseña el bus de eventos, contratos y políticas. El módulo de login necesita:
- Publicar eventos de autenticación (`usuario.autenticado`)
- Eventualmente consumir eventos relevantes (ej: `usuario.registrado`)

## Opciones consideradas

### Opción A: Implementar Kafka directamente

| Pros | Contras |
|------|---------|
| Solución final, production-ready | Requiere que el Grupo 1 defina contratos primero |
| Alto throughput | Acoplamiento temprano a una tecnología específica |
| | Complejidad de configuración (bootstrap servers, topics, ACLs) |

### Opción B: Implementar RabbitMQ directamente

| Pros | Contras |
|------|---------|
| Solución final | Mismos problemas que Kafka |
| Modelo de colas simple | |

### Opción C: Abstracción con placeholder (logging)

| Pros | Contras |
|------|---------|
| Desacoplado del broker específico | Eventos solo se loguean, no se entregan |
| Listo para integrar cuando el Grupo 1 publique el contrato | Requiere implementación futura |
| Interfaz limpia: solo cambiar la implementación | |
| Permite desarrollo y testing sin infraestructura de messaging | |

## Decisión

**Opción C: Abstracción con placeholder**

Elegimos esta opción porque:
1. **Independencia**: Podemos desarrollar y testear sin esperar al Grupo 1
2. **Desacoplamiento**: La interfaz `EventPublisher` no cambia — solo se reemplaza la implementación
3. **Testing**: Los tests pueden verificar que el evento se publica sin un broker real
4. **Migración futura**: Cuando el Grupo 1 defina contratos, solo creamos `KafkaEventPublisher`

## Consecuencias

- `LoggingEventPublisher` es un placeholder que serializa eventos a JSON y los loguea
- Cuando el Grupo 1 publique contratos, se crea `KafkaEventPublisher` o `RabbitMQEventPublisher`
- El `EventPublisher` interface no cambia — solo se agrega una nueva implementación
- Se puede usar `@Primary` o `@Profile` para seleccionar la implementación según ambiente
