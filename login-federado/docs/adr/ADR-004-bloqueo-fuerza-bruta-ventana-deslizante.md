# ADR-004: Bloqueo por fuerza bruta — ventana deslizante

## Estado: Aceptado

## Contexto

El endpoint de login es público (no requiere JWT). Un atacante puede intentar adivinar contraseñas por fuerza bruta. Se necesita protección que:
- Detecte y bloquee intentos repetidos
- No bloquee legítimos accidentalmente
- Se desbloquee automáticamente (sin intervención manual)
- Escale si hay múltiples instancias de la app

## Opciones consideradas

### Opción A: Contador simple con flag de bloqueo

| Pros | Contras |
|------|---------|
| Simple de implementar | Requiere desbloqueo manual o timer |
| | No distingue entre ráfagas legítimas y ataques |
| | Tabla crece indefinidamente (necesita cleanup) |

### Opción B: Ventana deslizante (sliding window)

| Pros | Contras |
|------|---------|
| Se desbloquea automáticamente (los intentos viejos "caen") | Más complejo de implementar |
| Captura la frecuencia real de intentos | Requiere índice en timestamp |
| No necesita desbloqueo manual | |
| NaturalmenteHandles ráfagas legítimas | |

### Opción C: Rate limiting por IP (Redis)

| Pros | Contras |
|------|---------|
| No depende de la BD | Requiere Redis como dependencia adicional |
| Muy rápido | No distingue entre usuarios (bloquea la IP, no la cuenta) |
| Escalable horizontalmente | Un atacante con IP rotativa lo evita |

### Opción D: CAPTCHA progresivo

| Pros | Contras |
|------|---------|
| No bloquea al usuario | Complejidad de integración |
| Distingue humanos de bots | Requiere servicio externo (reCAPTCHA) |
| UX aceptable | No es appropriado para API-only (sin UI) |

## Decisión

**Opción B: Ventana deslizante**

Elegimos ventana deslizante porque:
1. **Auto-desbloqueo**: Si el usuario espera 15 minutos, los intentos viejos caen de la ventana y puede volver a intentar
2. **Precisión**: Cuenta intentos fallidos en una ventana de tiempo, no un contador infinito
3. **Simplicidad operativa**: No necesita job de limpieza ni desbloqueo manual
4. **Sin dependencias extra**: Solo PostgreSQL (ya tenemos la BD)
5. **Auditoría**: Cada intento se registra en `login_attempts` para análisis forense

## Consecuencias

- Se mantiene una tabla `login_attempts` con historial de intentos
- La ventana es de 15 minutos con umbral de 5 intentos (configurable)
- Los registros viejos se pueden purgar periódicamente (no afecta la lógica)
- Futuro: se puede agregar Capa 2 con ML para detección de anomalías
