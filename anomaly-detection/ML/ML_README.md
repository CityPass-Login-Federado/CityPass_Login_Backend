# Módulo ML — Detección de anomalías con Isolation Forest

## 1. Objetivo

El módulo `ML` de `anomaly-detection` es una prueba de concepto orientada a demostrar la posibilidad de utilizar Machine Learning para detectar comportamientos anómalos asociados a intentos de autenticación en CityPass.

Esta primera versión utiliza **Isolation Forest**, un algoritmo de aprendizaje no supervisado diseñado para identificar observaciones que se alejan del comportamiento habitual.

El módulo fue construido de forma **independiente de la lógica productiva actual**. Las reglas existentes siguen siendo las responsables del comportamiento real del sistema. El modelo de Machine Learning no bloquea usuarios, no modifica sesiones y no reemplaza las reglas actuales.

Su objetivo es disponer de:

- un dataset sintético;
- un proceso de feature engineering;
- un modelo entrenado;
- métricas de evaluación;
- una herramienta simple para probar eventos manualmente.

---

## 2. Arquitectura general

La arquitectura actual puede entenderse como dos caminos separados.

### Flujo productivo

```text
Intento de autenticación
        |
        v
Datos / eventos del sistema
        |
        v
Reglas actuales
        |
        v
Decisiones productivas
```

### Flujo experimental de Machine Learning

```text
Dataset sintético
        |
        v
Feature engineering
        |
        v
Isolation Forest
        |
        v
Predicción
NORMAL / ANOMALY
```

El flujo experimental no altera el flujo productivo.

---

## 3. Estructura de carpetas

Cada archivo tiene una responsabilidad específica.

---

## 4. `features.py`

El archivo `features.py`, ubicado fuera de `ML`, contiene las features utilizadas por la lógica actual de detección.

Las features son:

```text
hour_of_day
is_new_ip
is_new_device
recent_failures_15min
history_size
```

### `hour_of_day`

Representa la hora en la que ocurre el intento de autenticación.

Ejemplo:

```text
03:25 -> 3
14:10 -> 14
```

Permite que el modelo aprenda cuáles son los horarios más frecuentes y cuáles aparecen con menor frecuencia.

### `is_new_ip`

Indica si la dirección IP ya apareció previamente en el historial del usuario.

```text
0 = IP conocida
1 = IP nueva
```

### `is_new_device`

Indica si el `user_agent` ya fue observado previamente para ese usuario.

```text
0 = dispositivo / agente conocido
1 = dispositivo / agente nuevo
```

### `recent_failures_15min`

Representa la cantidad de intentos fallidos encontrados durante los últimos 15 minutos.

### `history_size`

Representa la cantidad de registros históricos disponibles para el usuario, con un máximo de 200 registros según la lógica utilizada actualmente.

---

## 5. Dataset sintético

Debido a que el proyecto todavía no dispone de una cantidad suficiente de datos reales para entrenar y demostrar un modelo, se utiliza un dataset generado artificialmente.

El dataset contiene:

```text
10.000 eventos totales
9.500 eventos normales
500 anomalías
```

Por lo tanto:

95% normal
5% anomalías

La proporción de anomalías es configurable mediante:

```python
ANOMALY_RATIO = 0.05
```

---

## 6. Tipos de anomalías

En la primera versión se utilizan cuatro tipos de anomalías.

Cada tipo contiene 125 registros, lo que da un total de 500 anomalías.

### `multiple_changes`

Combina varias señales anómalas en un mismo evento, por ejemplo:

- horario poco habitual;
- IP nueva;
- dispositivo nuevo;
- cambio de cliente.

Es el tipo de anomalía más evidente para el modelo porque combina varias características alejadas del comportamiento habitual.

### `new_ip`

Simula un intento de autenticación desde una IP que no pertenece al conjunto habitual del usuario.

Está directamente relacionado con:

```text
is_new_ip
```

### `unusual_client`

Simula actividad desde un cliente o dispositivo diferente al habitual.

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

---


## 8. `config.py`

`config.py` centraliza la configuración del módulo.

Entre sus principales valores se encuentran:

```python
TOTAL_RECORDS = 10_000
ANOMALY_RATIO = 0.05
RANDOM_SEED = 42
```

También contiene las rutas utilizadas por los diferentes scripts:

```text
data/synthetic_events.csv
data/processed_features.csv
models/isolation_forest.pkl
```

El uso de una semilla fija permite obtener resultados reproducibles.

---

## 9. `generate_dataset.py`

Este script genera el dataset sintético.

Sus responsabilidades principales son:

1. Crear usuarios ficticios.
2. Asignar a cada usuario un comportamiento habitual.
3. Generar eventos normales.
4. Generar anomalías controladas.
5. Etiquetar los registros sintéticos.
6. Guardar el resultado en CSV.

Cada usuario posee un perfil habitual, por ejemplo:

```text
usuario: user042
departamento: reclamos
IP habitual: 10.x.x.x
cliente habitual: citypass-admin-web
user agent habitual: Chrome-Windows
horario habitual: 08:00 - 18:00
```

Los eventos normales siguen aproximadamente ese perfil.

Las anomalías modifican una o varias características.

---

## 10. Columnas del dataset crudo

El archivo:

```text
ML/data/synthetic_events.csv
```

contiene información similar a la estructura de los eventos de autenticación utilizados por CityPass.

Entre sus columnas se encuentran:

```text
eventId
eventType
occurredAt
userSub
username
department
clientId
chainId
ipAddress
userAgent
successful
synthetic_label
anomaly_type
```

### Columnas experimentales

Las siguientes columnas existen únicamente para construir y evaluar la PoC:

```text
synthetic_label
anomaly_type
```

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
unusual_client
unusual_hour
multiple_changes
```

Estas columnas **no se utilizan como entrada del modelo**.

---

## 11. `preprocess.py`

`preprocess.py` transforma el dataset crudo en las mismas features utilizadas por el modelo.

El archivo generado es:

```text
ML/data/processed_features.csv
```

Las columnas utilizadas para entrenamiento son:

```text
hour_of_day
is_new_ip
is_new_device
recent_failures_15min
history_size
```

Además se conservan:

```text
synthetic_label
anomaly_type
```

exclusivamente para evaluar posteriormente el resultado.

---

## 12. Historial de usuarios

El preprocessing mantiene un historial independiente para cada usuario.

Se utiliza conceptualmente un historial máximo de:

```text
200 eventos
```

para conservar coherencia con la lógica utilizada por `features.py`.

Para cada evento se calcula primero su vector de features y recién después se incorpora el evento al historial.

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

Esto puede producir falsos positivos.

Es una limitación esperable en sistemas de detección basados en comportamiento, ya que el modelo necesita cierto historial antes de poder conocer el patrón normal de un usuario.

---

## 14. `train.py`

`train.py` entrena el modelo utilizando `IsolationForest` de scikit-learn.

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

El valor:

```text
0.05
```

indica que se espera aproximadamente un 5% de observaciones anómalas.

Esto coincide con la proporción definida en el dataset sintético.

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

Las etiquetas sintéticas se utilizan únicamente después del entrenamiento para medir qué tan bien las predicciones coinciden con las anomalías introducidas artificialmente.

---

## 16. Modelo generado

Una vez entrenado, el modelo se almacena en:

```text
ML/models/isolation_forest.pkl
```

Se utiliza `joblib` para serializarlo.

Esto permite reutilizar el modelo sin volver a entrenarlo cada vez que se quiere realizar una predicción.

---

## 17. `evaluate.py`

`evaluate.py` carga:

```text
processed_features.csv
isolation_forest.pkl
```

y compara las predicciones del modelo con las etiquetas sintéticas conocidas.

Isolation Forest devuelve:

```text
1  = normal
-1 = anomalía
```

Para facilitar la evaluación se convierten a:

```text
0 = normal
1 = anomalía
```

---

## 18. Resultados obtenidos

La evaluación actual fue realizada sobre:

```text
10.000 registros
500 anomalías sintéticas
```

El modelo produjo:

```text
Predicted anomalies: 494
```

La matriz de confusión obtenida fue:

```text
True negatives:  9397
False positives: 103
False negatives: 109
True positives:  391
```

### Interpretación

#### True Positive

Evento generado como anomalía y correctamente identificado como anomalía.

```text
391
```

#### True Negative

Evento normal correctamente reconocido como normal.

```text
9397
```

#### False Positive

Evento normal identificado incorrectamente como anomalía.

```text
103
```

#### False Negative

Anomalía que el modelo no logró detectar.

```text
109
```

---

## 19. Métricas

Los resultados actuales son:

```text
Precision: 0.7915
Recall:    0.7820
F1-score:  0.7867
```

### Precision

```text
79.15%
```

Indica qué proporción de los eventos marcados como anomalía eran efectivamente anomalías sintéticas.

### Recall

```text
78.20%
```

Indica qué proporción de las 500 anomalías sintéticas consiguió detectar el modelo.

### F1-score

```text
78.67%
```

Representa un balance entre Precision y Recall.

---

## 20. Detección por tipo de anomalía

Los resultados obtenidos fueron:

```text
multiple_changes : 100.0%
new_ip           : 96.8%
unusual_client   : 76.0%
unusual_hour     : 40.0%
```

### `multiple_changes`

```text
125 / 125
100%
```

El modelo detecta muy bien eventos que combinan varias señales anómalas.

### `new_ip`

```text
121 / 125
96.8%
```

La detección es alta debido a que existe una feature directamente relacionada:

```text
is_new_ip
```

### `unusual_client`

```text
95 / 125
76%
```

La detección depende principalmente de:

```text
is_new_device
```

No todos los cambios de `clientId` quedan representados directamente en las features actuales.

### `unusual_hour`

```text
50 / 125
40%
```

Esta es la anomalía más difícil para el modelo actual.

El modelo recibe únicamente:

```text
hour_of_day
```

y debe aprender por distribución cuáles horarios aparecen con menor frecuencia.

No existe actualmente una feature explícita como:

```text
is_unusual_hour
```

porque se busca permitir que Isolation Forest aprenda el patrón por sí mismo.

---

## 21. `decision_score`

Además de la clasificación, Isolation Forest proporciona un `decision_score`.

La interpretación general utilizada es:

```text
score más negativo -> comportamiento más anómalo
score más positivo -> comportamiento más normal
```

---

## 22. `evaluation_results.csv`

Después de ejecutar `evaluate.py`, se genera:

```text
ML/data/evaluation_results.csv
```

Este archivo incluye las features originales junto con:

```text
predicted_anomaly
decision_score
```

Esto permite revisar manualmente:

- verdaderos positivos;
- verdaderos negativos;
- falsos positivos;
- falsos negativos;
- score de anomalía de cada registro.

---

## 23. `predict.py`

`predict.py` permite probar manualmente un evento contra el modelo ya entrenado.

El script recibe las features mediante parámetros de consola.

Formato:

```bash
python predict.py   --hour <hora>   --new-ip <0|1>   --new-device <0|1>   --failures <cantidad>   --history <cantidad>
```

En Windows / PowerShell también puede escribirse en una sola línea.

---

## 24. Ejemplo de evento normal

```bash
python predict.py --hour 11 --new-ip 0 --new-device 0 --failures 0 --history 35
```

Representa aproximadamente:

```text
Hora: 11
IP conocida
Dispositivo conocido
Sin fallos recientes
Historial suficiente
```

El resultado esperado es generalmente:

```text
Prediction: NORMAL
```

---

## 25. Ejemplo de evento intermedio

```bash
python predict.py --hour 18 --new-ip 1 --new-device 0 --failures 0 --history 20
```

Representa:

```text
Horario razonable
IP nueva
Dispositivo conocido
Sin fallos recientes
Historial existente
```

El resultado dependerá de los patrones aprendidos por el modelo.

---

## 26. Ejemplo de evento claramente anómalo

```bash
python predict.py --hour 2 --new-ip 1 --new-device 1 --failures 4 --history 50
```

Representa:

```text
Horario muy poco habitual
IP nueva
Dispositivo nuevo
Varios fallos recientes
Usuario con historial previo
```

El resultado esperado es generalmente:

```text
Prediction: ANOMALY
```

---

## 27. Ayuda de `predict.py`

Se puede consultar la ayuda con:

```bash
python predict.py --help
```

El script valida:

- hora entre 0 y 23;
- `new-ip` igual a 0 o 1;
- `new-device` igual a 0 o 1;
- cantidad de fallos no negativa;
- tamaño de historial no negativo.

---

## 28. Flujo completo de ejecución

Cuando se desea regenerar y volver a entrenar completamente el experimento se ejecuta:

```bash
python generate_dataset.py
python preprocess.py
python train.py
python evaluate.py
```

Luego pueden realizarse predicciones individuales con:

```bash
python predict.py --hour 2 --new-ip 1 --new-device 1 --failures 4 --history 50
```

---

## 29. Dependencias

El módulo utiliza principalmente:

```text
pandas
numpy
scikit-learn
joblib
```

Pueden instalarse mediante:

```bash
pip install -r requirements.txt
```

---

## 30. Relación con producción

Es importante distinguir la PoC de Machine Learning del comportamiento productivo actual.

Actualmente:

```text
Reglas existentes
        |
        v
Decisiones reales
```

El modelo ML:

```text
Isolation Forest
        |
        v
Resultado experimental
```

No reemplaza las reglas.

No bloquea usuarios.

No revoca sesiones.

No modifica decisiones de autenticación.

No interviene en el comportamiento productivo actual.

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

- las reglas podrían seguir funcionando como actualmente;
- el módulo de anomalías podría consumir los mismos eventos de forma independiente;
- ambos sistemas podrían evolucionar sin quedar fuertemente acoplados.

Actualmente Kafka todavía no forma parte de esta PoC.

---

## 32. Limitaciones actuales

La primera versión tiene varias limitaciones conocidas.

### Dataset sintético

Los datos utilizados para entrenar y evaluar no corresponden a tráfico real.

El objetivo es demostrar viabilidad técnica, no afirmar rendimiento productivo.

### Entrenamiento y evaluación sobre el mismo conjunto

La evaluación actual permite analizar el comportamiento de la PoC, pero no equivale a una validación productiva independiente.

Una versión posterior debería utilizar separación temporal entre datos de entrenamiento y validación.

### Cold start

Usuarios con poco o ningún historial pueden generar falsos positivos.

### Horarios inusuales

La feature `hour_of_day` por sí sola no permite detectar todos los casos de horario atípico.

### `clientId`

El `clientId` existe en los eventos sintéticos pero actualmente no se utiliza como feature directa.

### Datos reales

Antes de utilizar un modelo de este tipo en producción sería necesario entrenarlo y validarlo con datos históricos reales, representativos y correctamente tratados.

---

## 33. Conclusión

El módulo `ML` demuestra que los datos asociados a los intentos de autenticación de CityPass pueden transformarse en features comportamentales y ser utilizados por un modelo de detección de anomalías.

La PoC utiliza cinco features:

```text
hour_of_day
is_new_ip
is_new_device
recent_failures_15min
history_size
```

sobre un dataset de:

```text
10.000 registros
5% anomalías
```

El modelo Isolation Forest obtuvo:

```text
Precision: 79.15%
Recall:    78.20%
F1-score:  78.67%
```

Los mejores resultados se observaron en anomalías que afectan directamente las variables disponibles, especialmente cambios simultáneos y uso de nuevas direcciones IP.

Esta implementación no reemplaza el sistema de reglas actual. Su función es demostrar una posible evolución futura hacia un esquema híbrido donde reglas determinísticas y modelos de detección de anomalías puedan coexistir.
