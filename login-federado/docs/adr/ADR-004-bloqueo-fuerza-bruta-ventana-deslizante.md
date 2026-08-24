# ADR-004: Bloqueo por fuerza bruta — ventana deslizante

## Quiénes

| Nombre | Rol |
|--------|-----|
| Antonio Wu | Seguridad / Backend |

## Consideraciones

El endpoint de login es público (no requiere JWT). Un atacante puede intentar adivinar contraseñas por fuerza bruta. Se necesita protección que:
- Detecte y bloquee intentos repetidos
- No bloquee usuarios legítimos accidentalmente
- Se desbloquee automáticamente (sin intervención manual)
- Escale si hay múltiples instancias de la app

Restricciones y supuestos adicionales:
- Sin dependencias de infraestructura nuevas más allá de PostgreSQL (ya existente)
- La API no tiene UI, por lo que soluciones interactivas (CAPTCHA) quedan descartadas
- Debe dejar rastro auditable para análisis forense

### Opciones consideradas

#### Opción A: Contador simple con flag de bloqueo

| Pros | Contras |
|------|---------|
| Simple de implementar | Requiere desbloqueo manual o timer |
| | No distingue entre ráfagas legítimas y ataques |
| | Tabla crece indefinidamente (necesita cleanup) |

#### Opción B: Ventana deslizante (sliding window)

| Pros | Contras |
|------|---------|
| Se desbloquea automáticamente (los intentos viejos "caen") | Más complejo de implementar |
| Captura la frecuencia real de intentos | Requiere índice en timestamp |
| No necesita desbloqueo manual | |
| Maneja naturalmente ráfagas legítimas | |

#### Opción C: Rate limiting por IP (Redis)

| Pros | Contras |
|------|---------|
| No depende de la BD | Requiere Redis como dependencia adicional |
| Muy rápido | No distingue entre usuarios (bloquea la IP, no la cuenta) |
| Escalable horizontalmente | Un atacante con IP rotativa lo evita |

#### Opción D: CAPTCHA progresivo

| Pros | Contras |
|------|---------|
| No bloquea al usuario | Complejidad de integración |
| Distingue humanos de bots | Requiere servicio externo (reCAPTCHA) |
| UX aceptable | No es apropiado para API-only (sin UI) |

## Por todo esto, definimos

Implementar **ventana deslizante sobre la tabla `login_attempts` en PostgreSQL**: 5 intentos fallidos en una ventana de 15 minutos bloquean la cuenta temporalmente (parámetros configurables).

Razones principales:
1. **Auto-desbloqueo**: Si el usuario espera 15 minutos, los intentos viejos caen de la ventana y puede volver a intentar
2. **Precisión**: Cuenta intentos fallidos en una ventana de tiempo, no un contador infinito
3. **Simplicidad operativa**: No necesita job de limpieza ni desbloqueo manual
4. **Sin dependencias extra**: Solo PostgreSQL (ya tenemos la BD)
5. **Auditoría**: Cada intento se registra en `login_attempts` para análisis forense

## Consecuencias

### Positivas

- Desbloqueo automático sin intervención operativa ni falsos positivos permanentes
- Historial completo de intentos para auditoría y forensia
- Escala entre instancias porque el estado vive en la BD compartida, no en memoria local
- Parámetros (ventana/umbral) ajustables sin cambios de código

### Negativas

- Escrituras extra en BD en cada intento fallido
- La tabla `login_attempts` requiere purga periódica (aunque no afecta la lógica)
- No protege contra credential stuffing distribuido sobre muchas cuentas distintas
- Un umbral fijo puede frustrar a usuarios legítimos con errores repetidos

## Referencias (benchmark)

- NIST SP 800-63B — Digital Identity Guidelines (§5.2.2: rate limiting y lockout) — https://pages.nist.gov/800-63-3/sp800-63b.html
- OWASP — Authentication Cheat Sheet (Account Lockout) — https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP — Blocking Brute Force Attacks — https://owasp.org/www-community/controls/Blocking_Brute_Force_Attacks
- OWASP — Credential Stuffing Prevention Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Credential_Stuffing_Prevention_Cheat_Sheet.html
