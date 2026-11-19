# Módulo ML — Detección de anomalías con Isolation Forest V4

## 1. Objetivo

El módulo `ML` entrena y evalúa el modelo que CityPass consulta después de que LDAP valida las credenciales. Isolation Forest calcula un score a 
partir del historial de autenticación del usuario. La política V4 combina ese score con el contexto del intento para proponer `ALLOW`, `REVIEW` o `BLOCK`.

El modo `SHADOW` permite observar los bloqueos propuestos mientras el login continúa como `REVIEW`. En `ENFORCE`, el servicio devuelve la decisión propuesta. El backend registra ambas decisiones y el resultado del acceso.

---

## 2. Arquitectura general

La arquitectura combina entrenamiento offline e inferencia productiva.

### Flujo productivo

```text
LDAP acepta las credenciales
        |
        v
POST /score → historial del usuario en PostgreSQL → cinco variables
        |
        v
Isolation Forest V4 + política de riesgo → decisión propuesta
        |
        v
SHADOW / ENFORCE → decisión efectiva → backend de login
```

### Flujo de entrenamiento

```text
Eventos sintéticos por conjunto → cálculo del historial y las variables
        |
        +-- train      → ajustar Isolation Forest con accesos legítimos
        +-- validation → calibrar los umbrales de la política híbrida
        +-- test       → medir falsos positivos y detección con los umbrales fijados
```

El entrenamiento se ejecuta offline. Al arrancar, el servicio carga el modelo y la política versionados.

---

## 3. Estructura de carpetas

```text
anomaly-detection/
├── app/         # Cálculo de variables, inferencia y API
├── ML/          # Generación, entrenamiento, calibración y evaluación
│   ├── data/    # Eventos, variables y resultados por conjunto
│   └── models/  # Modelo y política V4 que empaqueta Docker
└── models/      # Modelo candidato para los experimentos offline
```

---

## 4. `features.py`

`app/features.py` consulta `login_attempts`. El orden de las cinco variables, el límite de historial y la ventana de fallos se comparten con el preprocesamiento offline. [Contrato de variables](../app/feature_contract.py)

Las features son:

```text
hour_of_day
is_new_ip
is_new_device
recent_failures_15min
history_size
```

### `hour_of_day`

Representa la hora UTC del intento de autenticación, de 0 a 23.

Ejemplo:

```text
03:25 -> 3
14:10 -> 14
```

Permite que el modelo aprenda cuáles son los horarios más frecuentes y cuáles aparecen con menor frecuencia.

### `is_new_ip`

Indica si la IP aparece en alguna autenticación exitosa del historial consultado.

```text
0 = IP conocida
1 = IP nueva
```

### `is_new_device`

Indica si el `user_agent` aparece en alguna autenticación exitosa previa del usuario. El agente se usa como indicador del dispositivo.

```text
0 = dispositivo / agente conocido
1 = dispositivo / agente nuevo
```

### `recent_failures_15min`

Cuenta los intentos fallidos previos con una antigüedad menor que 15 minutos dentro del historial consultado.

### `history_size`

Cantidad de intentos del usuario con fecha estrictamente anterior al intento actual. Incluye éxitos y fallos, hasta un máximo de 200 registros.

---

## 5. Dataset sintético

El generador V4 usa usuarios y semillas distintos para entrenamiento, validación y prueba. Las cifras de la tabla cuentan los intentos objetivo después de la preparación. El generador añade además eventos de historial y fallos previos para calcular las variables.

| Conjunto | Legítimos | Anomalías | Uso |
| --- | ---: | ---: | --- |
| Entrenamiento | 5.700 | 0 | Ajustar Isolation Forest |
| Validación | 1.900 | 100 | Elegir los umbrales de revisión y bloqueo |
| Prueba | 1.900 | 100 | Evaluar la política en usuarios independientes |

---

## 6. Tipos de anomalías

V4 genera cinco tipos de anomalías. Validación y prueba incluyen cada una 20 casos de cada tipo. También hay escenarios legítimos con cambios de entorno o errores de contraseña para medir falsas alarmas.

### `multiple_changes`

Combina un horario atípico, una IP nueva y un agente nuevo. El generador varía la cantidad de fallos previos.

### `new_ip`

Simula un intento de autenticación desde una IP que no pertenece al conjunto habitual del usuario.

Está directamente relacionado con:

```text
is_new_ip
```

### `new_device`

Simula un intento con un `user_agent` diferente del habitual.

La principal señal observable por el modelo es:

```text
is_new_device
```

### `unusual_hour`

Simula intentos de autenticación en horarios poco frecuentes, por ejemplo durante la madrugada.

La señal utilizada es:

```text
hour_of_day
```

### `recent_failures`

Simula tres fallos de autenticación antes de un intento que llega al detector. La señal asociada es `recent_failures_15min`.

---


## 8. `config.py`

`ML/config.py` define `DATA_DIR`, `MODELS_DIR` y la semilla de entrenamiento (`RANDOM_SEED = 42`). El generador V4 define sus propios conjuntos en `SPLITS`, con semillas 142, 143 y 144 para `train`, `validation` y `test`, respectivamente. Los umbrales de inferencia se leen de la [política V4](models/risk_policy_v4.json).

---

## 9. `generate_dataset_v4.py`

El script genera los eventos de un conjunto indicado con `--split`.

Sus responsabilidades principales son:

1. Crear usuarios ficticios.
2. Asignar a cada usuario un comportamiento habitual.
3. Generar eventos normales.
4. Generar anomalías controladas.
5. Etiquetar los registros sintéticos.
6. Guardar el resultado en CSV.

Cada usuario tiene una IP y un agente habituales, alternativas conocidas y un turno diurno, vespertino o nocturno. El identificador incorpora el conjunto, por ejemplo `v4-test-042`.

Los eventos normales siguen aproximadamente ese perfil.

Las anomalías modifican una o varias características.

---

## 10. Columnas del dataset crudo

El archivo:

```text
ML/data/synthetic_events_v4_<split>.csv
```

contiene información similar a la estructura de los eventos de autenticación utilizados por CityPass.

Entre sus columnas se encuentran:

```text
eventId
eventType
occurredAt
username
ipAddress
userAgent
successful
synthetic_label
anomaly_type
scenario
ml_eligible
evaluation_target
```

### Columnas experimentales

`scenario` identifica la situación simulada. `ml_eligible` señala los intentos que llegan al detector tras LDAP. `evaluation_target` distingue el intento objetivo de los eventos que aportan historial.

`synthetic_label` indica:

```text
0 = evento normal
1 = anomalía sintética
```

`anomaly_type` indica qué anomalía se introdujo artificialmente.

Ejemplos:

```text
normal
new_ip
new_device
recent_failures
unusual_hour
multiple_changes
```

Estas columnas **no se utilizan como entrada del modelo**.

---

## 11. `preprocess.py`

`preprocess.py` transforma el dataset crudo en las mismas features utilizadas por el modelo.

El archivo generado es:

```text
ML/data/processed_features_v4_<split>.csv
```

Las columnas utilizadas para entrenamiento son:

```text
hour_of_day
is_new_ip
is_new_device
recent_failures_15min
history_size
```

Se conservan las etiquetas y los datos de contexto. `ML.prepare_dataset_v4` selecciona los objetivos exitosos y elegibles, filtra los legítimos para entrenamiento y escribe `ML/data/<split>_v4.csv`.

---

## 12. Historial de usuarios

El preprocessing mantiene un historial independiente para cada usuario.

El preprocesamiento conserva los últimos 200 intentos de cada usuario. Calcula las variables de todos los eventos con el mismo instante UTC antes de agregarlos al historial. Así reproduce el filtro `attempted_at < :ts` de la consulta en PostgreSQL.

Esto es importante para evitar **data leakage**.

Ejemplo:

Si un usuario utiliza una IP por primera vez, esa IP debe ser considerada nueva en el momento de analizar el evento.

Si el evento se agregara al historial antes de calcular las features, la IP aparecería incorrectamente como conocida.

---

## 13. Cold start

Una limitación observada durante las pruebas es el problema de **cold start**.

Cuando un usuario todavía no tiene historial:

```text
history_size = 0
```

no existen IPs ni dispositivos conocidos.

Por lo tanto, su primer evento puede tener:

```text
is_new_ip = 1
is_new_device = 1
```

aunque el comportamiento sea legítimo.

En V4, un usuario con menos de cinco intentos previos puede recibir `REVIEW`. La política exige al menos cinco para proponer `BLOCK`.

---

## 14. `train.py`

`ML/train.py` entrena el modelo con `IsolationForest` de scikit-learn. En V4 recibe `ML/data/train_v4.csv`.

Las features utilizadas son:

```python
FEATURE_COLUMNS = [
    "hour_of_day",
    "is_new_ip",
    "is_new_device",
    "recent_failures_15min",
    "history_size",
]
```

La configuración principal utilizada es:

```python
IsolationForest(
    n_estimators=200,
    contamination=0.05,
    random_state=42,
    n_jobs=-1,
)
```

### `contamination`

`contamination=0.05` fija el punto de corte interno de Isolation Forest a partir de los scores de entrenamiento. Los 5.700 ejemplos usados en `fit` son legítimos. Los umbrales que deciden `REVIEW` y `BLOCK` se calibran después con el conjunto de validación.

---

## 15. Aprendizaje no supervisado

Isolation Forest es un algoritmo no supervisado.

Esto significa que durante:

```python
model.fit(X)
```

el modelo **no recibe** `synthetic_label`.

Las únicas variables que observa son:

```text
hour_of_day
is_new_ip
is_new_device
recent_failures_15min
history_size
```

La preparación usa `synthetic_label` para seleccionar ejemplos legítimos antes de `fit`. En validación y prueba, la etiqueta permite comparar la decisión propuesta con el escenario generado.

---

## 16. Modelo generado

El entrenamiento offline escribe el candidato en `models/isolation_forest_v4_candidate.pkl` cuando se ejecutan los comandos de la sección 28. La pareja versionada que utiliza el servicio está en:

```text
ML/models/isolation_forest_v4.pkl
ML/models/risk_policy_v4.json
```

El modelo se serializa con `joblib`. Al arrancar, el servicio comprueba el SHA-256 del modelo contra el registrado en la política, el orden de las variables, la semántica `successful_history_v1`, los umbrales y las condiciones de bloqueo. Si falta un archivo o falla una comprobación, el arranque falla. [Carga del modelo](../app/model.py)

---

## 17. `evaluate_hybrid_v4.py`

El evaluador carga `ML/data/test_v4.csv`, el modelo candidato y la política generada por `ML.calibrate_hybrid_v4`. Calcula la decisión propuesta para cada intento y compara dos criterios con `synthetic_label`: bloqueo (`BLOCK`) y marcado (`REVIEW` o `BLOCK`). También compara contra una regla que usa solo el contexto. La evaluación ocurre antes de aplicar `SHADOW` o `ENFORCE`.

---

## 18. Resultados obtenidos

La prueba independiente contiene 2.000 intentos objetivo. La tabla muestra las decisiones propuestas por la política calibrada.

| Etiqueta sintética | `ALLOW` | `REVIEW` | `BLOCK` | Total |
| --- | ---: | ---: | ---: | ---: |
| Legítimo | 1.720 | 156 | 24 | 1.900 |
| Anomalía | 64 | 29 | 7 | 100 |

### Interpretación

Para el criterio de **marcado**, cuentan tanto las revisiones como los bloqueos propuestos.

#### True Positive

36 anomalías fueron marcadas (29 revisiones y 7 bloqueos).

#### True Negative

1.720 intentos legítimos recibieron `ALLOW`.

#### False Positive

180 intentos legítimos fueron marcados (156 revisiones y 24 bloqueos).

#### False Negative

64 anomalías recibieron `ALLOW`.

---

## 19. Métricas

| Criterio | Precision en prueba | Recall en prueba | F1 en prueba | Falsos positivos en validación | Falsos positivos en prueba | Objetivo |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Bloqueo propuesto | 22,58 % | 7,00 % | 10,69 % | 17/1.900 (0,89 %) | 24/1.900 (1,26 %) | ≤ 1 % |
| Marcado (`REVIEW` o `BLOCK`) | 16,67 % | 36,00 % | 22,78 % | 188/1.900 (9,89 %) | 180/1.900 (9,47 %) | ≤ 10 % |

La tasa de falsos positivos se calcula sobre los intentos legítimos. En prueba, los bloqueos propuestos superaron el límite del 1 % y el marcado quedó dentro del objetivo del 10 %. La política versionada conserva las métricas de **validación**. [Política V4](models/risk_policy_v4.json)

### Precision

Proporción de anomalías entre los intentos que cumplen el criterio. Para bloqueo, 7 de los 31 bloqueos propuestos correspondieron a anomalías.

### Recall

Proporción de las 100 anomalías que cumplen el criterio. Se marcaron 36 y se propuso bloquear 7 de ellas.

### F1-score

Media armónica de precision y recall para el mismo criterio.

---

## 20. Detección por tipo de anomalía

Cada fila corresponde a 20 anomalías del conjunto de prueba. Las columnas cuentan decisiones propuestas.

| Escenario | `ALLOW` | `REVIEW` | `BLOCK` |
| --- | ---: | ---: | ---: |
| `multiple_changes` | 1 | 12 | 7 |
| `new_ip` | 18 | 2 | 0 |
| `new_device` | 16 | 4 | 0 |
| `unusual_hour` | 20 | 0 | 0 |
| `recent_failures` | 9 | 11 | 0 |

### `multiple_changes`

Este escenario concentró los siete bloqueos propuestos entre las anomalías de prueba.

### `new_ip`

Dos de los 20 cambios de IP recibieron `REVIEW`.

### `new_device`

Cuatro de los 20 cambios de agente recibieron `REVIEW`.

### `unusual_hour`

Los 20 intentos en horarios atípicos recibieron `ALLOW`.

### `recent_failures`

Once de los 20 intentos precedidos por fallos recibieron `REVIEW`.

---

## 21. `decision_score`

Isolation Forest calcula `decision_score` mediante `decision_function`. Un score más negativo indica un comportamiento más anómalo. El servicio lo transforma en un indicador de riesgo entre 0 y 1 con `risk_score = clip(0.5 - decision_score, 0, 1)`.

| `risk_score` | Decisión propuesta |
| --- | --- |
| Inferior a 0,479 | `ALLOW` |
| Desde 0,479 hasta menos de 0,543 | `REVIEW` |
| Desde 0,543 | `BLOCK` si se cumplen las cuatro condiciones de contexto. En cualquier otro caso, `REVIEW`. |

Las cuatro condiciones deben coincidir en el mismo intento: al menos cinco intentos previos en el historial, IP nueva, agente nuevo y al menos dos fallos en los últimos 15 minutos. [Política de decisión](../app/risk_policy.py)

---

## 22. `evaluation_results_v4.csv`

`ML.evaluate_hybrid_v4` genera `ML/data/evaluation_results_v4.csv`. Cada fila contiene las cinco variables, la etiqueta sintética, el escenario y el resultado de evaluar la política.

```text
decision_score
risk_score
context_eligible
decision
```

Esto permite revisar manualmente:

- verdaderos positivos;
- verdaderos negativos;
- falsos positivos;
- falsos negativos;
- score de anomalía de cada registro.

---

## 23. `predict.py`

`ML.predict` se conserva para reproducir la primera versión. Carga `ML/models/isolation_forest.pkl` y devuelve `NORMAL` o `ANOMALY`. Para probar manualmente V4 con las mismas cinco variables, abrí una consola interactiva desde `anomaly-detection/` con las dependencias de la sección 29.

```bash
python -i -c "from app.model import score_details; from app.feature_contract import FEATURE_ORDER"
```

Los tres ejemplos siguientes se pueden pegar en esa consola. Usan el modelo y la política versionados, con `ANOMALY_MODE=SHADOW` (valor predeterminado).

---

## 24. Ejemplo de evento normal

```python
r = score_details(dict(zip(FEATURE_ORDER, [11, 0, 0, 0, 35])))
print(round(r['risk_score'], 4), r['proposed_decision'], r['decision'])
```

Representa aproximadamente:

```text
Hora: 11
IP conocida
Dispositivo conocido
Sin fallos recientes
Historial suficiente
```

La salida V4 es `0.2863 ALLOW ALLOW`.

---

## 25. Ejemplo de evento intermedio

```python
r = score_details(dict(zip(FEATURE_ORDER, [18, 1, 0, 0, 20])))
print(round(r['risk_score'], 4), r['proposed_decision'], r['decision'])
```

Representa:

```text
Horario razonable
IP nueva
Dispositivo conocido
Sin fallos recientes
Historial existente
```

La salida V4 es `0.372 ALLOW ALLOW`. La IP nueva por sí sola deja este ejemplo por debajo del umbral de revisión.

---

## 26. Ejemplo de evento claramente anómalo

```python
r = score_details(dict(zip(FEATURE_ORDER, [2, 1, 1, 4, 50])))
print(round(r['risk_score'], 4), r['proposed_decision'], r['decision'])
```

Representa:

```text
Horario muy poco habitual
IP nueva
Dispositivo nuevo
Varios fallos recientes
Usuario con historial previo
```

La salida V4 es `0.5719 BLOCK REVIEW`. Se cumplen las condiciones de bloqueo y `SHADOW` convierte la propuesta en revisión. En `ENFORCE`, la decisión efectiva de este mismo ejemplo es `BLOCK`.

---

## 27. Ayuda de `predict.py`

La ayuda del predictor histórico se consulta desde `anomaly-detection/`:

```bash
python -m ML.predict --help
```

El script valida:

- hora entre 0 y 23;
- `new-ip` igual a 0 o 1;
- `new-device` igual a 0 o 1;
- cantidad de fallos no negativa;
- tamaño de historial no negativo.

---

## 28. Flujo completo de ejecución

Desde `anomaly-detection/`, con las dependencias de la sección 29 instaladas, ejecutá en PowerShell:

```powershell
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

La calibración escribe `ML/models/risk_policy_v4_candidate.metadata.json`. La evaluación lee esa política y el modelo candidato. La prueba de estrés los aplica a casos concretos, incluidos cambios legítimos de entorno.

---

## 29. Dependencias

El módulo utiliza principalmente:

```text
pandas
numpy
scikit-learn
joblib
```

Usá Python 3.12 y las versiones fijadas en `anomaly-detection/requirements.txt`, incluido scikit-learn 1.5.2. Desde `anomaly-detection/`:

```bash
python -m pip install -r requirements.txt
```

---

## 30. Relación con producción

El Compose de producción del repositorio declara `ANOMALY_MODE=SHADOW`. La respuesta de `POST /score` incluye el riesgo y sus razones, `proposed_decision`, `decision`, el modo, las cinco variables, su semántica, la versión del modelo y los hashes SHA-256 del modelo y de la política. `GET /health` informa el modelo cargado, el modo y los umbrales. [Compose de producción](../docker-compose.prod.yml) · [Contrato de la API](../app/schemas.py)

```text
Decisión propuesta      SHADOW       ENFORCE
ALLOW                   ALLOW        ALLOW
REVIEW                  REVIEW       REVIEW
BLOCK                   REVIEW       BLOCK
```

Spring registra la evaluación en `anomaly_assessments`, junto con la decisión propuesta, la efectiva y el resultado del acceso (`PERMITTED_BY_ML`, `BLOCKED` o `APPROVED_OVERRIDE`). Ante un bloqueo efectivo, el backend consume una aprobación administrativa válida o registra el evento de bloqueo y rechaza el intento. Si falla la consulta al servicio de anomalías, el login responde HTTP 503 en ambos modos. [Flujo de login](../../login-federado/src/main/java/citypass/loginfederado/service/AuthService.java)

---

## 31. Posible evolución futura

Una arquitectura futura podría permitir que los eventos de autenticación sean enviados mediante Kafka a consumidores independientes.

Por ejemplo:

```text
                    +-----------------> Rules Engine
                    |
RawAuthenticationEvent -> Kafka
                    |
                    +-----------------> Anomaly Detection
                                           |
                                           v
                                   Isolation Forest
```

De esta forma:

- los consumidores podrían analizar eventos ya emitidos;
- la evaluación durante el login seguiría requiriendo una decisión antes de emitir los tokens.

El flujo V4 implementado consulta el riesgo por HTTP después de validar las credenciales.

---

## 32. Limitaciones actuales

La evaluación de V4 tiene las siguientes limitaciones.

### Dataset sintético

El entrenamiento, la calibración y la prueba usan escenarios generados artificialmente. Las tasas observadas describen esos escenarios.

### Evaluación con conjuntos independientes

Los usuarios y las semillas cambian entre entrenamiento, validación y prueba. En la prueba independiente, la tasa de bloqueos falsos aumentó del 0,89 % observado en validación al 1,26 %, por encima del objetivo del 1 %.

### Cold start

Con poco historial, un intento legítimo puede merecer revisión. El mínimo de cinco intentos previos limita los bloqueos propuestos por la política.

### Horarios inusuales

La feature `hour_of_day` por sí sola no permite detectar todos los casos de horario atípico.

### `clientId`

El backend asocia la evaluación al `clientId` del login para auditoría. El modelo calcula el score con las cinco variables de la sección 4.

### Datos reales

La validación con tráfico real queda pendiente. El modo `SHADOW` conserva los bloqueos propuestos para poder analizar qué accesos legítimos habrían resultado afectados.

---

## 33. Reentrenamiento con datos reales

El repositorio incluye un comando administrativo offline que exporta los intentos
persistidos en `login_attempts`, construye las mismas cinco features usadas durante
la inferencia y reemplaza el artefacto solamente cuando el entrenamiento finaliza
correctamente.

La conexión debe usar una cuenta PostgreSQL de solo lectura:

```bash
cd anomaly-detection
export ANOMALY_DB_URL='postgresql://usuario_lectura:clave@host:5432/login_federado'
python train.py --from 2026-01-01T00:00:00Z --to 2026-11-01T00:00:00Z
```

En PowerShell, desde `anomaly-detection/`, la variable se define con:

```powershell
$env:ANOMALY_DB_URL = "postgresql://usuario_lectura:clave@host:5432/login_federado"
python train.py --from 2026-01-01T00:00:00Z --to 2026-11-01T00:00:00Z
```

El proceso genera:

- `ML/data/real_events.csv`: exportación cruda sensible;
- `ML/data/real_processed_features.csv`: features calculadas;
- `ML/models/isolation_forest.pkl`, salida predeterminada del reentrenamiento administrativo. El Dockerfile empaqueta la pareja V4 indicada en la sección 16.
- `ML/models/isolation_forest.metadata.json`: trazabilidad del entrenamiento.

Los dos CSV reales y el archivo de metadata están ignorados por Git. Los intentos
fallidos participan del historial para calcular `recent_failures_15min`, pero por
defecto solamente los logins exitosos se utilizan en `model.fit`. Para impedir un
reemplazo accidental con una muestra insuficiente, el comando exige al menos 100
logins exitosos; el mínimo puede ajustarse explícitamente con `--min-records`.

También es posible ejecutar cada etapa por separado:

```bash
python export_real_events.py --from 2026-01-01T00:00:00Z
python -m ML.preprocess --input ML/data/real_events.csv --output ML/data/real_processed_features.csv
python -m ML.train --input ML/data/real_processed_features.csv
```

Los datos reales no poseen etiquetas confiables de fraude. Por ese motivo el
entrenamiento reporta distribución de scores y tasa de anomalías, pero no presenta
precision, recall o F1 como si fueran métricas reales. La evaluación etiquetada del
dataset sintético se conserva como benchmark separado.

---

## 34. Conclusión

Isolation Forest V4 combina cinco variables de comportamiento con una política de riesgo calibrada. En la prueba sintética independiente se marcaron 36 de 100 anomalías. Se propuso bloquear 24 de 1.900 accesos legítimos (1,26 %), por encima del objetivo del 1 %.

El modo `SHADOW` permite registrar esas propuestas y contrastarlas con los resultados del acceso antes de aplicar bloqueos automáticos en `ENFORCE`.
