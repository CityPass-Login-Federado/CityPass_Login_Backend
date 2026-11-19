# ADR-009: Detección híbrida de anomalías de login con Isolation Forest V4

## Quiénes

| Nombre | Rol |
|--------|-----|
| Nicolas Hernandez| Backend |

## Consideraciones

Después de que LDAP valida las credenciales y antes de emitir los tokens, el backend consulta `POST /score` para evaluar el riesgo del intento. Un usuario legítimo también puede cambiar de IP, estrenar un dispositivo o equivocarse varias veces de contraseña. Un bloqueo automático en esas condiciones tiene un costo directo para el usuario.

El servicio calcula cinco variables a partir de los últimos 200 intentos del usuario con fecha estrictamente anterior al intento evaluado. El entrenamiento y la inferencia usan la misma definición de historial (`successful_history_v1`). [Contrato de variables](../../../anomaly-detection/app/feature_contract.py) · [Consulta del historial](../../../anomaly-detection/app/features.py)

- `hour_of_day` es la hora UTC del intento.
- `is_new_ip` indica si la IP carece de accesos exitosos en ese historial.
- `is_new_device` aplica el mismo criterio al `user_agent`, usado como indicador de dispositivo.
- `recent_failures_15min` cuenta los fallos previos con una antigüedad inferior a 15 minutos.
- `history_size` cuenta los intentos previos consultados, incluidos éxitos y fallos.

Restricciones y supuestos:

- La detección no debe bloquear por sí sola el acceso sin una política explícita.
- El modelo puede no estar disponible durante un despliegue o una incidencia.
- Las decisiones deben ser explicables para facilitar auditoría y soporte.
- El modelo debe funcionar con datos mayoritariamente normales y pocos ejemplos etiquetados de fraude.
- El resultado debe normalizarse a un score entre `0` y `1`.

### Opciones consideradas

#### Opción A: Reglas deterministas únicamente

| Pros | Contras |
|------|---------|
| Fácil de explicar y auditar | Detecta peor patrones combinados |
| No necesita entrenamiento | Requiere ajustar manualmente los umbrales |
| Funciona sin modelo | Puede generar más falsos positivos |
| Comportamiento predecible | No aprende de los datos históricos |

#### Opción B: Clasificador supervisado

| Pros | Contras |
|------|---------|
| Puede alcanzar buena precisión con datos etiquetados | Requiere un conjunto de datos confiable |
| Permite optimizar métricas concretas | El fraude real suele estar poco representado |
| Puede incorporar muchas señales | Requiere mantener etiquetas y procesos de reentrenamiento |

#### Opción C: Isolation Forest

| Pros | Contras |
|------|---------|
| Adecuado para detección no supervisada | No explica completamente cada anomalía |
| No requiere etiquetas de fraude | El score necesita calibración |
| Detecta combinaciones inusuales de señales | Puede cambiar su comportamiento al variar los datos |
| Disponible en scikit-learn | Requiere monitoreo y reentrenamiento |

#### Opción D: Enfoque híbrido

| Pros | Contras |
|------|---------|
| Combina explicabilidad y detección estadística | Tiene mayor complejidad que usar solo reglas |
| Exige señales de contexto antes de proponer un bloqueo | Requiere validar el modelo y su política como una unidad |
| Permite observar bloqueos propuestos antes de aplicarlos | La evaluación con datos sintéticos deja abierta la validación con tráfico real |

## Por todo esto, definimos

Adoptamos **Isolation Forest V4 con una política de bloqueo contextual**. El modelo permite aprender de accesos legítimos con pocas etiquetas de fraude disponibles. La política exige que un score alto coincida con señales observables antes de proponer una medida que impida el acceso.

El servicio calcula `risk_score = clip(0.5 - decision_score, 0, 1)`. Un valor mayor indica mayor anomalía. La política versionada determina la decisión propuesta. [Umbrales y condiciones de V4](../../../anomaly-detection/ML/models/risk_policy_v4.json)

| Riesgo | Decisión propuesta |
| --- | --- |
| Inferior a 0,479 | `ALLOW` |
| Desde 0,479 hasta menos de 0,543 | `REVIEW` |
| Desde 0,543 | `BLOCK` si coinciden una IP nueva, un agente nuevo, al menos cinco intentos previos y al menos dos fallos recientes. Si falta alguna condición, `REVIEW`. |

**Aplicación al login.** En `SHADOW`, un `BLOCK` propuesto se devuelve como `REVIEW` efectivo y el login puede continuar. En `ENFORCE`, la decisión efectiva coincide con la propuesta. Ante un bloqueo efectivo, el backend consume una aprobación administrativa válida o registra el evento de bloqueo y rechaza el intento. El Compose de producción del repositorio declara `ANOMALY_MODE=SHADOW`. [Compose](../../../anomaly-detection/docker-compose.prod.yml) · [Flujo de login](../../src/main/java/citypass/loginfederado/service/AuthService.java)

**Auditoría.** El servicio devuelve el score, los motivos, las variables, el modo, la versión y las huellas SHA-256 del modelo y la política. Spring guarda en `anomaly_assessments` la evaluación completa, la decisión propuesta, la efectiva y el resultado del acceso (`PERMITTED_BY_ML`, `BLOCKED` o `APPROVED_OVERRIDE`). Esto permite identificar qué accesos se habrían bloqueado en `SHADOW`. [Registro de evaluaciones](../../src/main/java/citypass/loginfederado/service/AnomalyAssessmentService.java)

**Entrenamiento y despliegue.** V4 se ajusta offline con 5.700 intentos sintéticos legítimos. Los umbrales se calibran en validación y se evalúan en un tercer conjunto con usuarios independientes. El reentrenamiento administrativo con `login_attempts` usa por defecto los accesos exitosos para el ajuste y conserva los fallos como contexto. Docker empaqueta `ML/models/isolation_forest_v4.pkl` y `ML/models/risk_policy_v4.json`. Al arrancar, el servicio valida el hash del modelo contra la política, la compatibilidad de las variables y las condiciones de decisión. [Guía de entrenamiento y evaluación](../../../anomaly-detection/ML/ML_README.md) · [Carga de los artefactos](../../../anomaly-detection/app/model.py)

## Consecuencias

### Positivas

- Un usuario con menos de cinco intentos previos puede recibir una revisión. El mínimo de historial evita proponer bloqueos durante sus primeros accesos.
- Operaciones puede reconstruir qué versión y qué señales motivaron una decisión, incluso cuando una aprobación administrativa permitió el acceso.

### Negativas

- En la prueba sintética independiente, la política propuso bloquear 24 de 1.900 accesos legítimos (1,26 %), por encima del objetivo del 1 %. Marcó para revisión o bloqueo 180 de esos 1.900 (9,47 %), dentro del objetivo del 10 %. De 100 anomalías, 29 recibieron `REVIEW` y 7 recibieron `BLOCK`. Queda pendiente medir estas tasas con tráfico real. [Resultados y reproducción](../../../anomaly-detection/ML/ML_README.md)
- El login depende de la disponibilidad del detector en ambos modos. Un modelo o una política ausentes o incompatibles impiden que el detector arranque. Si falla la consulta al detector, el backend responde HTTP 503. [Cliente del detector](../../src/main/java/citypass/loginfederado/security/AnomalyRiskClient.java) · [Manejo de errores](../../src/main/java/citypass/loginfederado/exception/GlobalExceptionHandler.java)

## Referencias (benchmark)

- Isolation Forest — https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.IsolationForest.html
- scikit-learn User Guide — https://scikit-learn.org/stable/user_guide.html
- OWASP Machine Learning Security Top Ten — https://owasp.org/www-project-machine-learning-security-top-10/
- NIST AI Risk Management Framework — https://www.nist.gov/itl/ai-risk-management-framework
