# Detección de anomalías de login con Isolation Forest V4

CityPass evalúa el riesgo de un intento de login después de que LDAP acepta las credenciales. El microservicio calcula cinco variables a partir del historial del usuario, obtiene un score de Isolation Forest y propone `ALLOW`, `REVIEW` o `BLOCK`. En modo **`SHADOW`**, un bloqueo propuesto se registra como tal y el login continúa como `REVIEW`. 

## Qué observa el modelo

El entrenamiento offline y la inferencia comparten el orden de las variables y la semántica `successful_history_v1`. Para cada intento se consideran hasta 200 eventos del mismo usuario con fecha estrictamente anterior, en UTC. Dos eventos con el mismo instante quedan fuera del historial mutuo. [Contrato de variables](../app/feature_contract.py) · [Preprocesamiento offline](preprocess.py) · [Consulta en PostgreSQL](../app/features.py)

| Variable | Cálculo |
| --- | --- |
| `hour_of_day` | Hora UTC del intento, de 0 a 23. |
| `is_new_ip` | Vale 1 si la IP carece de accesos **exitosos** en el historial considerado. |
| `is_new_device` | Aplica el mismo criterio al `user_agent`, usado como indicador de dispositivo. |
| `recent_failures_15min` | Cantidad de fallos previos con una antigüedad inferior a 15 minutos. |
| `history_size` | Cantidad de intentos previos consultados, incluidos éxitos y fallos. |

Un intento fallido desde una IP nueva aumenta el contador de fallos recientes. La IP y el agente recién pasan a ser conocidos después de una autenticación exitosa desde ese entorno. En el primer login, ambas señales de novedad pueden valer 1 y el historial vale 0.

## Cómo se decide el riesgo

El servicio transforma `decision_function` de Isolation Forest con `risk_score = clip(0.5 - decision_score, 0, 1)`. Un valor mayor indica mayor anomalía. Los umbrales de la política versionada determinan la decisión propuesta. [Política V4](models/risk_policy_v4.json) · [Implementación](../app/risk_policy.py)

| Score | Contexto | Decisión propuesta |
| --- | --- | --- |
| Menor que 0,479 | Cualquiera | `ALLOW` |
| Desde 0,479 hasta menos de 0,543 | Cualquiera | `REVIEW` |
| Desde 0,543 | Al menos 5 intentos previos, IP y agente nuevos, y al menos 2 fallos recientes | `BLOCK` |
| Desde 0,543 | Falta alguna de esas condiciones | `REVIEW` |

`ANOMALY_MODE` determina qué decisión recibe el backend como efectiva. En `SHADOW` (valor predeterminado), solo cambia el bloqueo propuesto, que pasa a `REVIEW`. En `ENFORCE` se aplica la decisión propuesta. El backend permite continuar con `ALLOW` y `REVIEW`. Ante un `BLOCK` efectivo, consume una aprobación administrativa válida o registra el bloqueo y rechaza el intento. Si falla la consulta al microservicio, el login responde HTTP 503 en ambos modos. 

## Artefactos, API y auditoría

- La imagen incluye `ML/models/isolation_forest_v4.pkl` y `ML/models/risk_policy_v4.json`. Al arrancar, el servicio comprueba el SHA-256 del modelo contra la política y valida la compatibilidad de scikit-learn, las variables, los umbrales y las condiciones de bloqueo. Un artefacto ausente o incompatible impide el arranque. [Dockerfile](../Dockerfile) · [Carga y validaciones](../app/model.py)
- `POST /score` recibe el intento y devuelve `risk_score`, `reasons`, `proposed_decision`, `decision`, `mode`, `features`, `feature_semantics`, `model_version`, `model_sha256` y `policy_sha256`. `GET /health` expone el modelo cargado, su versión, modo y umbrales. [API](../app/main.py) · [Contrato](../app/schemas.py)
- Spring guarda en `anomaly_assessments` la evaluación, las decisiones propuesta y efectiva, y el resultado del acceso (`PERMITTED_BY_ML`, `BLOCKED` o `APPROVED_OVERRIDE`). Si rechaza el intento, lo vincula al evento de bloqueo mediante `blocked_event_id`. Esto permite distinguir los bloqueos simulados de los aplicados. [Persistencia](../../login-federado/src/main/java/citypass/loginfederado/service/AnomalyAssessmentService.java)

## Entrenamiento, calibración y prueba

V4 genera tres conjuntos sintéticos con usuarios y semillas distintos. Cada conjunto incluye historial previo, fallos que aportan contexto e intentos objetivo. La preparación selecciona los objetivos exitosos que habrían llegado al detector después de LDAP. Se conservan cambios legítimos de IP, dispositivo y horario para poder medir falsas alarmas. [Generación](generate_dataset_v4.py) · [Selección de objetivos](prepare_dataset_v4.py)

| Conjunto | Intentos utilizados | Uso |
| --- | ---: | --- |
| Entrenamiento | 5.700 legítimos | Ajustar Isolation Forest con las cinco variables. |
| Validación | 1.900 legítimos y 100 anomalías | Calibrar los umbrales de revisión y bloqueo. |
| Prueba independiente | 1.900 legítimos y 100 anomalías | Evaluar la política con los umbrales ya elegidos. |

Las etiquetas sintéticas permiten seleccionar los ejemplos legítimos de entrenamiento y medir el desempeño en validación y prueba. El modelo usa únicamente las cinco variables de comportamiento en `fit`.

### Resultados de la política V4

La tasa de falsos positivos se calcula sobre los 1.900 intentos legítimos de cada conjunto. «Marcado» abarca tanto `REVIEW` como `BLOCK`. La evaluación mide la decisión **propuesta**, antes de aplicar el modo del servicio. [Métricas de validación](models/risk_policy_v4.json) · [Evaluación independiente](evaluate_hybrid_v4.py)

| Criterio | Objetivo de falsos positivos | Validación | Prueba independiente | Anomalías detectadas en prueba |
| --- | ---: | ---: | ---: | ---: |
| Bloqueo propuesto | ≤ 1 % | 17/1.900 (0,89 %) | 24/1.900 (1,26 %) | 7/100 |
| Marcado para revisión o bloqueo | ≤ 10 % | 188/1.900 (9,89 %) | 180/1.900 (9,47 %) | 36/100 |

En la prueba independiente, los marcados quedaron dentro del objetivo y los bloqueos propuestos superaron el límite del 1 %. Son resultados sobre intentos sintéticos. `SHADOW` permite observar las propuestas sobre tráfico real antes de habilitar bloqueos automáticos.

### Reproducir el experimento

Desde `anomaly-detection/`, con Python 3.12 y las versiones de [requirements.txt](../requirements.txt), ejecutar en PowerShell:

```powershell
python -m pip install -r requirements.txt
foreach ($split in 'train', 'validation', 'test') {
    python -m ML.generate_dataset_v4 --split $split
    python -m ML.preprocess --input "ML/data/synthetic_events_v4_${split}.csv" --output "ML/data/processed_features_v4_${split}.csv"
    python -m ML.prepare_dataset_v4 --split $split
}
python -m ML.train --input ML/data/train_v4.csv --output models/isolation_forest_v4_candidate.pkl --metadata-output ML/models/isolation_forest_v4_candidate.metadata.json
python -m ML.calibrate_hybrid_v4
python -m ML.evaluate_hybrid_v4
python -m ML.stress_test_v4
```

El entrenamiento genera el candidato en `models/`. La calibración escribe `ML/models/risk_policy_v4_candidate.metadata.json` y la evaluación guarda una fila por intento en `ML/data/evaluation_results_v4.csv`, con su etiqueta, escenario, score, contexto y decisión. La prueba de estrés muestra casos concretos, incluidos cambios legítimos de entorno. La imagen utiliza la pareja versionada descrita arriba.

## Herramientas de versiones anteriores y datos reales

La prueba de concepto original se conserva en `ML.generate_dataset`, `ML.evaluate` y `ML.predict`. Este último carga `ML/models/isolation_forest.pkl` y devuelve `NORMAL` o `ANOMALY`. Los scripts V2 y V3 documentan las comparaciones de variables y políticas que precedieron a V4.

Para experimentar con datos históricos, el comando administrativo `python train.py --from <inicio-ISO> --to <fin-ISO>` se ejecuta desde `anomaly-detection/` con `ANOMALY_DB_URL` de solo lectura. Exporta `login_attempts`, calcula las variables y por defecto exige al menos 100 logins exitosos para el ajuste. Su salida predeterminada es `ML/models/isolation_forest.pkl`. La calibración y evaluación de una política V4 son etapas separadas.
