# Módulo ML — Isolation Forest v4

**Estado documentado:** implementación actual (*as-is*)

**Fecha de corte:** 19 de noviembre de 2026

**Versión del modelo:** `v4`

**Modo operativo predeterminado y productivo:** `SHADOW`

## 1. Alcance

El microservicio `anomaly-detection` evalúa el contexto de un login ya validado por LDAP. La versión v4 usa Isolation Forest y una política híbrida para devolver un `risk_score`, una decisión propuesta y una decisión efectiva.

El modelo no valida contraseñas, no emite tokens y no representa una probabilidad de fraude. El backend Java conserva la responsabilidad de permitir o rechazar el login.

La evidencia disponible sigue basada en datos sintéticos. El repositorio incluye herramientas para exportar y reentrenar con intentos reales, pero no contiene evidencia versionada de un reentrenamiento con datos reales etiquetados.

## 2. Flujo productivo

```mermaid
flowchart LR
    LOGIN[Login con LDAP válido] --> HISTORY[Historial en login_attempts]
    HISTORY --> FEATURES[Cinco features]
    FEATURES --> MODEL[Isolation Forest v4]
    MODEL --> SCORE[risk_score normalizado]
    SCORE --> POLICY[Política híbrida v4]
    POLICY --> PROPOSED[proposed_decision]
    PROPOSED --> MODE{ANOMALY_MODE}
    MODE -->|SHADOW + BLOCK| REVIEW[decision = REVIEW]
    MODE -->|SHADOW + ALLOW/REVIEW| SAME[decision sin cambios]
    MODE -->|ENFORCE| ENFORCED[decision propuesta]
```

`POST /score` devuelve tanto `proposed_decision` como `decision`. En `SHADOW`, un bloqueo propuesto se convierte en `REVIEW`; el resultado queda trazable, pero el modelo no impide el acceso. En `ENFORCE`, la decisión propuesta se aplica sin esa conversión.

## 3. Artefactos desplegados

| Archivo | Función |
|---|---|
| `ML/models/isolation_forest_v4.pkl` | Modelo versionado |
| `ML/models/risk_policy_v4.json` | Umbrales, contexto, métricas y hash del modelo |
| `app/model.py` | Carga, validación, inferencia y modo operativo |
| `app/risk_policy.py` | Normalización y decisión híbrida |
| `app/features.py` | Feature engineering en runtime |
| `app/feature_contract.py` | Orden y contrato de features |

El Dockerfile copia los dos artefactos v4 a `/app/models`. El arranque es **fail-fast**: si falta el modelo o la política, si el SHA-256 no coincide, si cambia el orden de features o si la política es incompatible, el servicio no inicia.

El modelo fue serializado para el runtime fijado en `anomaly-detection/requirements.txt`, que usa `scikit-learn==1.5.2`. La carga transforma `InconsistentVersionWarning` en error; la suite v4 verifica que el artefacto se carga sin esa advertencia.

## 4. Contrato de features

El orden es parte del contrato:

```text
hour_of_day
is_new_ip
is_new_device
recent_failures_15min
history_size
```

| Feature | Semántica v4 |
|---|---|
| `hour_of_day` | Hora UTC del intento, de 0 a 23 |
| `is_new_ip` | `1` si la IP normalizada no aparece en autenticaciones exitosas previas |
| `is_new_device` | `1` si el user-agent normalizado no aparece en autenticaciones exitosas previas |
| `recent_failures_15min` | Fallos previos dentro de una ventana de 900 segundos |
| `history_size` | Cantidad de intentos previos recuperados, con máximo 200 |

La semántica declarada por la política es `successful_history_v1`: los intentos fallidos aportan contexto para fallos recientes, pero no convierten una IP o un dispositivo en confiables. La consulta excluye eventos con timestamp igual o posterior al intento evaluado y ordena por fecha e id para evitar fuga temporal.

## 5. Score y política híbrida

Isolation Forest produce un `decision_score`. El runtime lo transforma con:

```text
risk_score = clip(0.5 - decision_score, 0, 1)
```

Un valor mayor indica mayor rareza según el modelo; no es una probabilidad calibrada de ataque.

La política productiva versionada define:

| Parámetro | Valor |
|---|---:|
| Umbral de `REVIEW` | `0.479` |
| Umbral de `BLOCK` | `0.543` |
| FPR máximo de bloqueo durante calibración | `0.01` |
| FPR máximo para eventos marcados durante calibración | `0.10` |

Un score sobre el umbral de bloqueo solo conserva `BLOCK` si también se cumplen todas las condiciones contextuales:

- al menos 5 eventos de historial;
- IP nueva;
- dispositivo nuevo;
- al menos 2 fallos recientes.

Si falta cualquiera de esas señales, el resultado se degrada a `REVIEW`. Esta política reduce bloqueos por *cold start* o por una señal aislada.

## 6. Modos `SHADOW` y `ENFORCE`

| Modo | Comportamiento |
|---|---|
| `SHADOW` | `BLOCK` propuesto se expone como `proposed_decision`, pero la `decision` efectiva es `REVIEW` |
| `ENFORCE` | La `decision` efectiva coincide con la propuesta |

La variable es:

```text
ANOMALY_MODE=SHADOW
```

`SHADOW` es el valor por defecto del código y el valor explícito de `docker-compose.prod.yml`. Cambiar a `ENFORCE` requiere una decisión operativa consciente, validación con datos representativos y monitoreo de falsos positivos.

El backend Java persiste las evaluaciones recibidas. Solo una `decision=BLOCK` activa el flujo de bloqueo y aprobación administrativa; en el modo productivo actual los `BLOCK` propuestos por v4 llegan como `REVIEW` y no bloquean el login.

## 7. Contrato HTTP

### `GET /health`

Además del estado del servicio informa versión, modo, features, hashes y umbrales del modelo cargado.

Ejemplo abreviado:

```json
{
  "status": "ok",
  "model": {
    "loaded": true,
    "version": "v4",
    "mode": "SHADOW",
    "feature_semantics": "successful_history_v1",
    "review_threshold": 0.479,
    "block_threshold": 0.543
  }
}
```

### `POST /score`

Request:

```json
{
  "username": "jperez",
  "ip": "203.0.113.10",
  "user_agent": "Mozilla/5.0",
  "timestamp": "2026-11-19T15:00:00Z",
  "success": true
}
```

Response abreviada:

```json
{
  "risk_score": 0.57,
  "decision": "REVIEW",
  "proposed_decision": "BLOCK",
  "reasons": [
    "Score calculado por Isolation Forest V4",
    "SHADOW: bloqueo propuesto no aplicado"
  ],
  "model_version": "v4",
  "mode": "SHADOW",
  "feature_semantics": "successful_history_v1",
  "features": {
    "hour_of_day": 15,
    "is_new_ip": 1,
    "is_new_device": 1,
    "recent_failures_15min": 2,
    "history_size": 30
  }
}
```

La respuesta real también incluye `model_sha256` y `policy_sha256` para trazabilidad.

## 8. Dataset y separación de datos v4

`generate_dataset_v4.py` crea cohortes independientes por semilla, usuarios y período:

| Split | Semilla | Usuarios | Targets por usuario | Targets totales |
|---|---:|---:|---:|---:|
| Train | 142 | 300 | 20 | 6.000 |
| Validation | 143 | 200 | 10 | 2.000 |
| Test | 144 | 200 | 10 | 2.000 |

Cada split simula comportamiento legítimo, *cold start*, cambios válidos de entorno, fallos recientes y cinco tipos de anomalía. Los usuarios y períodos separados reducen contaminación entre entrenamiento, calibración y evaluación.

`prepare_dataset_v4.py` conserva solo eventos que llegan al punto de evaluación ML. Para `train` usa únicamente targets exitosos y legítimos (`synthetic_label=0`). Validation y test conservan ambas clases para calibrar y medir la política.

## 9. Métricas v4 versionadas

`risk_policy_v4.json` conserva las métricas de la calibración de la política híbrida sobre 2.000 targets de validation: 100 anomalías sintéticas y 1.900 casos legítimos.

| Salida evaluada | TP | FP | TN | FN | Precision | Recall | FPR | F1 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `BLOCK` | 5 | 17 | 1.883 | 95 | 22,73 % | 5,00 % | 0,895 % | 8,20 % |
| Marcados (`REVIEW` o `BLOCK`) | 43 | 188 | 1.712 | 57 | 18,61 % | 43,00 % | 9,895 % | 25,98 % |

Estas cifras no son métricas de fraude real ni prometen desempeño productivo. Reflejan el objetivo conservador de la política: limitar el FPR de bloqueo y observar más casos mediante revisión. En producción `SHADOW` agrega una barrera adicional: ningún `BLOCK` propuesto por el modelo se aplica.

### Antecedente v1/v3

Las cifras P 79,15 %, R 78,20 % y F1 78,67 % pertenecen al experimento sintético anterior, con otra generación y otra forma de evaluar anomalías. Se conservan solo como antecedente histórico y **no describen el modelo v4 desplegado**.

## 10. Reproducción del pipeline v4

Instalar las dependencias fijadas del runtime:

```bash
cd anomaly-detection
python -m pip install -r requirements.txt
```

Generar, transformar y preparar cada split:

```bash
python -m ML.generate_dataset_v4 --split train
python -m ML.preprocess --input ML/data/synthetic_events_v4_train.csv --output ML/data/processed_features_v4_train.csv
python -m ML.prepare_dataset_v4 --split train

python -m ML.generate_dataset_v4 --split validation
python -m ML.preprocess --input ML/data/synthetic_events_v4_validation.csv --output ML/data/processed_features_v4_validation.csv
python -m ML.prepare_dataset_v4 --split validation

python -m ML.generate_dataset_v4 --split test
python -m ML.preprocess --input ML/data/synthetic_events_v4_test.csv --output ML/data/processed_features_v4_test.csv
python -m ML.prepare_dataset_v4 --split test
```

Entrenar el candidato, calibrar con validation y evaluar con test:

```bash
python -m ML.train --input ML/data/train_v4.csv --output models/isolation_forest_v4_candidate.pkl --metadata-output models/isolation_forest_v4_candidate.metadata.json --contamination 0.05
python -m ML.calibrate_hybrid_v4
python -m ML.evaluate_hybrid_v4
python -m ML.stress_test_v4
```

La promoción a `ML/models/isolation_forest_v4.pkl` y `ML/models/risk_policy_v4.json` debe preservar el par modelo/política y su hash; no se debe reemplazar un archivo sin el otro.

## 11. Reentrenamiento con datos reales

El comando administrativo existente exporta `login_attempts`, construye las cinco features y entrena un artefacto offline:

```powershell
$env:ANOMALY_DB_URL = "postgresql://usuario_lectura:clave@host:5432/login_federado"
python train.py --from 2026-01-01T00:00:00Z --to 2026-11-01T00:00:00Z
```

Debe usarse una cuenta PostgreSQL de solo lectura. Los CSV reales contienen información sensible, están ignorados y no deben subirse al repositorio. El comando exige al menos 100 logins exitosos por defecto.

Ese flujo heredado genera `isolation_forest.pkl`; no promueve automáticamente un bundle v4 ni reemplaza la calibración, evaluación y aprobación operativa exigidas para el modelo desplegado.

## 12. Testing

La CI ejecuta:

```bash
cd anomaly-detection
python -m compileall -q app ML export_real_events.py train.py tests
python -m unittest discover -s tests -v
```

Al corte existen **62 métodos de prueba** en diez archivos. Cubren contrato temporal, confianza basada en éxitos, normalización, política híbrida, carga y hash del modelo, modos `SHADOW`/`ENFORCE`, API, pipeline v4 y casos de estrés.

La auditoría del 19/11/2026 informa 62 pruebas aprobadas y carga con scikit-learn 1.5.2 sin warnings. El conteo no reemplaza el enlace al run verde y SHA que se presenten como evidencia de entrega.

## 13. Limitaciones y controles pendientes

- Dataset y etiquetas de referencia sintéticos; falta validación con datos reales representativos.
- No existe evidencia versionada de monitoreo de deriva, tasas reales de revisión o falsos positivos.
- `SHADOW` evita bloqueos del modelo, pero exige observar `proposed_decision`, `decision`, versión y hashes.
- El score mide rareza, no probabilidad ni causalidad.
- La activación de `ENFORCE` requiere criterios de aprobación, rollback y monitoreo definidos.
- El pipeline de datos reales heredado no produce por sí solo el bundle v4 listo para producción.

## 14. Referencias internas

- `../../login-federado/docs/adr/ADR-009-ia-ml-deteccion-anomalias.md`
- `../README.md`
- `../app/model.py`
- `../app/risk_policy.py`
- `../tests/`
