# ADR-006: Event bus — placeholder con abstracción para Kafka/RabbitMQ

## Quiénes

| Nombre | Rol |
|--------|-----|
| Antonio Wu | Seguridad / Backend |

## Consideraciones

CityPass+ requiere comunicación asincrónica entre módulos (EDA — Event Driven Architecture). El Grupo 1 diseña el bus de eventos, contratos y políticas. El módulo de login necesita:
- Publicar eventos de autenticación (`usuario.autenticado`)
- Eventualmente consumir eventos relevantes (ej: `usuario.registrado`)

Restricciones y supuestos adicionales:
- La tecnología del broker no depende de este módulo: la decide el Grupo 1
- No podemos bloquear nuestro desarrollo ni nuestro testing esperando esa definición
- El código de dominio no debe acoplarse a una API de broker concreta

### Opciones consideradas

#### Opción A: Implementar Kafka directamente

| Pros | Contras |
|------|---------|
| Solución final, production-ready | Requiere que el Grupo 1 defina contratos primero |
| Alto throughput | Acoplamiento temprano a una tecnología específica |
| | Complejidad de configuración (bootstrap servers, topics, ACLs) |

#### Opción B: Implementar RabbitMQ directamente

| Pros | Contras |
|------|---------|
| Solución final | Mismos problemas que Kafka |
| Modelo de colas simple | |

#### Opción C: Abstracción con placeholder (logging)

| Pros | Contras |
|------|---------|
| Desacoplado del broker específico | Eventos solo se loguean, no se entregan |
| Listo para integrar cuando el Grupo 1 publique el contrato | Requiere implementación futura |
| Interfaz limpia: solo cambiar la implementación | |
| Permite desarrollo y testing sin infraestructura de messaging | |

## Por todo esto, definimos

Definir una **interfaz `EventPublisher` con una implementación placeholder (`LoggingEventPublisher`)** que serializa los eventos a JSON y los registra en logs. La migración a Kafka o RabbitMQ será una nueva implementación sin cambios en el dominio.

Razones principales:
1. **Independencia**: Podemos desarrollar y testear sin esperar al Grupo 1
2. **Desacoplamiento**: La interfaz `EventPublisher` no cambia — solo se reemplaza la implementación
3. **Testing**: Los tests pueden verificar que el evento se publica sin un broker real
4. **Migración futura**: Cuando el Grupo 1 defina contratos, solo creamos `KafkaEventPublisher` o `RabbitMQEventPublisher`

## Consecuencias

### Positivas

- Desarrollo y testing inmediatos, sin infraestructura de messaging
- Dominio desacoplado de la tecnología de transporte
- Selección de implementación por ambiente vía `@Primary`/`@Profile`
- Integración futura de bajo costo: agregar una clase, tocar cero código de negocio

### Negativas

- Los eventos hoy solo se loguean: no hay entrega real ni durabilidad
- Riesgo de que el placeholder viva más de lo previsto (deuda técnica visible)
- Dependemos de decisiones del Grupo 1 para cerrar la integración
- Doble esfuerzo: abstracción ahora + implementación real después

## Referencias (benchmark)

- Gregor Hohpe & Bobby Woolf — Enterprise Integration Patterns (Addison-Wesley) — https://www.enterpriseintegrationpatterns.com/
- Martin Fowler — What do you mean by “Event-Driven”? — https://martinfowler.com/articles/201701-event-driven.html
- Spring Cloud Stream (abstracción sobre brokers de mensajería) — https://spring.io/projects/spring-cloud-stream
- Apache Kafka Documentation — https://kafka.apache.org/documentation/
- RabbitMQ Tutorials — https://www.rabbitmq.com/tutorials
