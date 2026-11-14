# ADR-004: Lockout por ventana deslizante y auditoría administrativa

## Estado

Accepted — 14 de noviembre de 2026.

## Contexto y Problema

El login público necesita limitar adivinación de contraseñas sin un flag permanente, y toda mutación LDAP delegada debe ser atribuible. Ambas necesidades requieren persistencia compartida entre instancias.

## Opciones Consideradas (Pros y Contras de cada una)

### Contador/flag en memoria

- Pros: rápido y simple.
- Contras: se pierde al reiniciar y diverge entre réplicas.

### Redis/rate limiting por IP

- Pros: alta velocidad y expiración natural.
- Contras: nueva dependencia; una IP compartida causa falsos positivos y no atribuye por usuario.

### Ventana SQL por usuario

- Pros: estado compartido, historial forense y desbloqueo automático.
- Contras: escritura por intento, tabla creciente y posible abuso distribuido sobre muchas cuentas.

### Auditoría por logs

- Pros: mínima implementación.
- Contras: búsqueda y retención dependen de plataforma externa; difícil consulta estructurada.

### Tabla append-oriented

- Pros: actor/acción/objetivo estructurados y consultables.
- Contras: requiere política de integridad, acceso y retención.

## Decisión Tomada y Justificación

Guardar cada intento en `login_attempts`; antes de autenticar, contar fallos del mismo username posteriores a `now - 15 min` y bloquear desde cinco. No hay entidad `LoginLockout` ni desbloqueo manual: la ventana expira automáticamente.

Guardar las mutaciones del panel en `panel_audit` con `actor_sub`, `actor_uid`, módulo, acción, objetivo, detalle y fecha. Deshabilitar una persona además revoca todos sus refresh activos.

## Consecuencias Técnicas

- El lockout se comparte entre instancias y deja evidencia, pero requiere purga/retención y no limita por IP.
- El error externo es el mismo 401 que credenciales inválidas.
- `panel_audit` no tiene endpoint de consulta ni protección append-only en la base.
- `PanelAuditService` captura y registra excepciones sin fallar la mutación; el comentario que promete fallo conjunto no coincide con el comportamiento.
- LDAP y SQL no tienen atomicidad distribuida: una operación puede completarse aunque falle su auditoría.
