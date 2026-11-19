# ADR-009: Detección híbrida v4 con Isolation Forest y modo Shadow

**Estado:** aceptada e implementada

**Última actualización:** 19 de noviembre de 2026

## Quiénes

| Nombre | Rol |
|---|---|
| Nicolas Hernandez | Backend / IA |

## Contexto

El flujo de autenticación necesita identificar intentos potencialmente anómalos después de validar las credenciales LDAP y antes de emitir tokens. Las señales disponibles son hora, IP, dispositivo, fallos recientes e historial del usuario.

El sistema dispone de pocos ejemplos reales etiquetados de ataque. Un bloqueo incorrecto afecta disponibilidad y soporte, por lo que la introducción de ML debe ser gradual, observable y reversible.

La versión v4 agrega un modelo Isolation Forest, una política calibrada y dos resultados distintos:

- `proposed_decision`: resultado que propone el modelo y la política;
- `decision`: resultado efectivo que consume `login-federado`.

## Opciones consideradas

| Opción | Ventajas | Desventajas |
|---|---|---|
| Reglas deterministas | Explicables y sin entrenamiento | Ajuste manual y menor capacidad para patrones combinados |
| Clasificador supervisado | Optimiza métricas conocidas | Requiere etiquetas reales confiables y representativas |
| Isolation Forest directo | No requiere etiquetas de fraude | Score no probabilístico y riesgo de falsos positivos |
| Política híbrida + Shadow | Permite calibrar, observar y activar gradualmente | Mayor complejidad y necesidad de monitoreo |

## Decisión

Adoptar **Isolation Forest v4 con política híbrida** y desplegarlo inicialmente en modo `SHADOW`.

### Contrato de features

El orden forma parte del contrato:

```text
hour_of_day
is_new_ip
is_new_device
recent_failures_15min
history_size
```

La semántica `successful_history_v1` considera confiables únicamente IP y user-agent observados en autenticaciones exitosas previas. Los fallos anteriores sí se usan para la ventana de 15 minutos. El historial se limita a 200 eventos y excluye registros con timestamp igual o posterior al evento evaluado.

### Artefactos

El bundle productivo está formado por:

- `ML/models/isolation_forest_v4.pkl`;
- `ML/models/risk_policy_v4.json`.

El Dockerfile copia ambos a `/app/models`. El runtime no reentrena y no usa un fallback silencioso: valida presencia, hash, política, semántica y features, y falla al iniciar si el bundle es incompatible. `requirements.txt` fija `scikit-learn==1.5.2`.

### Score y umbrales

El score se calcula con `clip(0.5 - decision_score, 0, 1)`. No representa probabilidad de fraude.

La política versionada define:

- `REVIEW` desde `0.479`;
- `BLOCK` desde `0.543`;
- FPR objetivo de bloqueo menor o igual a 1 % durante calibración;
- FPR objetivo combinado de revisión/bloqueo menor o igual a 10 % durante calibración.

Un `BLOCK` también exige todas estas señales: historial mínimo 5, IP nueva, dispositivo nuevo y al menos 2 fallos recientes. Sin ese contexto, se degrada a `REVIEW`.

### Modo operativo

`ANOMALY_MODE` admite:

| Valor | Decisión efectiva |
|---|---|
| `SHADOW` | Convierte una propuesta `BLOCK` en `REVIEW` y conserva la propuesta para observabilidad |
| `ENFORCE` | Aplica la decisión propuesta |

`SHADOW` es el valor por defecto y el configurado en el Compose productivo. Solo una `decision=BLOCK` activa el bloqueo y la aprobación administrativa del backend; por ello, los bloqueos propuestos por el modelo v4 no se aplican en el modo productivo actual.

### Trazabilidad

`POST /score` devuelve score, razones, decisión propuesta, decisión efectiva, versión, hashes, semántica, modo y vector de features. `/health` expone el estado del bundle y sus umbrales.

## Evidencia de calibración

La política v4 versionada se calibró sobre 2.000 targets sintéticos de validation: 100 anomalías y 1.900 casos legítimos.

| Salida | Precision | Recall | FPR | F1 |
|---|---:|---:|---:|---:|
| `BLOCK` | 22,73 % | 5,00 % | 0,895 % | 8,20 % |
| `REVIEW` o `BLOCK` | 18,61 % | 43,00 % | 9,895 % | 25,98 % |

Las cifras P 79,15 %, R 78,20 % y F1 78,67 % corresponden a un experimento anterior y no son evidencia del modelo v4 desplegado. Ninguna métrica sintética debe presentarse como rendimiento sobre fraude real.

## Consecuencias

### Positivas

- El modo Shadow permite observar bloqueos propuestos sin afectar accesos.
- El modelo y la política quedan unidos por SHA-256 y contrato de features.
- La política exige contexto adicional antes de proponer bloqueos.
- La respuesta conserva evidencia suficiente para auditoría técnica.
- El runtime y la CI usan scikit-learn 1.5.2; la suite verifica carga y compatibilidad.

### Negativas y riesgos

- El dataset y las etiquetas disponibles siguen siendo sintéticos.
- El score expresa rareza, no causalidad ni probabilidad.
- En `SHADOW`, el sistema observa pero no previene ataques únicamente detectados por ML.
- Activar `ENFORCE` sin validación real puede introducir falsos bloqueos.
- El pipeline heredado de reentrenamiento con `login_attempts` genera `isolation_forest.pkl`; no promueve automáticamente el bundle v4.

## Criterios para pasar a `ENFORCE`

1. Validación temporal con datos reales representativos y tratamiento de privacidad.
2. Monitoreo de `proposed_decision`, `decision`, tasas de revisión y falsos positivos.
3. Criterios de aceptación, responsable de aprobación y plan de rollback documentados.
4. Verificación del SHA-256 y versión desplegados.
5. Evidencia de pruebas y smoke test asociados al mismo commit.

## Referencias

- `anomaly-detection/ML/ML_README.md`
- `anomaly-detection/ML/models/risk_policy_v4.json`
- `anomaly-detection/app/model.py`
- [Isolation Forest — scikit-learn](https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.IsolationForest.html)
- [NIST AI Risk Management Framework](https://www.nist.gov/itl/ai-risk-management-framework)
- [OWASP Machine Learning Security Top Ten](https://owasp.org/www-project-machine-learning-security-top-10/)
