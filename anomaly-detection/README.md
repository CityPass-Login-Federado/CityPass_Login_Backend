# Anomaly Detection — FastAPI + Isolation Forest v4

**Estado:** servicio integrado al flujo de login

**Fecha de corte:** 19 de noviembre de 2026

**Modo productivo:** `SHADOW`

## Responsabilidad

El servicio analiza el contexto de un intento de login después de que el backend valida las credenciales en LDAP y antes de emitir tokens. Consulta `login_attempts`, construye cinco features y aplica el modelo y la política híbrida v4.

No autentica usuarios, no modifica LDAP, no emite JWT y no escribe intentos de login. Esas responsabilidades pertenecen a `login-federado`.

## Arquitectura

```mermaid
sequenceDiagram
    participant C as Cliente
    participant B as login-federado
    participant L as LDAP
    participant A as anomaly-detection
    participant P as PostgreSQL

    C->>B: POST /auth/login
    B->>L: validar usuario, módulo y contraseña
    L-->>B: credenciales válidas
    B->>A: POST /score
    A->>P: leer hasta 200 intentos previos
    P-->>A: historial
    A->>A: features + Isolation Forest v4 + política
    A-->>B: score, proposed_decision, decision y trazabilidad
    B->>P: persistir evaluación e intento
    alt decision efectiva = BLOCK
        B-->>C: rechazo controlado
    else ALLOW o REVIEW
        B-->>C: tokens
    end
```

La llamada es sincrónica y *fail-closed*: si el evaluador no responde, el backend no interpreta la falla como autorización.

## Endpoints

### `GET /health`

Informa salud y estado del bundle cargado:

```json
{
  "status": "ok",
  "model": {
    "loaded": true,
    "version": "v4",
    "mode": "SHADOW",
    "features": [
      "hour_of_day",
      "is_new_ip",
      "is_new_device",
      "recent_failures_15min",
      "history_size"
    ],
    "feature_semantics": "successful_history_v1",
    "review_threshold": 0.479,
    "block_threshold": 0.543
  }
}
```

La respuesta real también expone el SHA-256 del modelo.

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

Campos principales de la respuesta:

| Campo | Significado |
|---|---|
| `risk_score` | Rareza normalizada entre 0 y 1; no es probabilidad de fraude |
| `proposed_decision` | Resultado de la política v4 antes del modo operativo |
| `decision` | Resultado efectivo que procesa el backend |
| `reasons` | Señales y conversión por `SHADOW`, si corresponde |
| `model_version` | Versión del modelo (`v4`) |
| `model_sha256` / `policy_sha256` | Trazabilidad del bundle |
| `feature_semantics` | Contrato semántico (`successful_history_v1`) |
| `mode` | `SHADOW` o `ENFORCE` |
| `features` | Vector exacto evaluado |

## Features

| Feature | Cálculo |
|---|---|
| `hour_of_day` | Hora UTC del intento |
| `is_new_ip` | La IP no aparece en logins exitosos previos |
| `is_new_device` | El user-agent no aparece en logins exitosos previos |
| `recent_failures_15min` | Fallos anteriores en los últimos 900 segundos |
| `history_size` | Cantidad de intentos previos consultados, máximo 200 |

Solo las autenticaciones exitosas establecen confianza para IP y dispositivo. Los fallos sí aportan contexto a `recent_failures_15min`. La consulta solo considera eventos anteriores al timestamp evaluado.

## Política v4

El score se obtiene con:

```text
risk_score = clip(0.5 - decision_score, 0, 1)
```

Los umbrales versionados son `0.479` para `REVIEW` y `0.543` para `BLOCK`. Un bloqueo además exige historial de al menos 5 eventos, IP nueva, dispositivo nuevo y 2 o más fallos recientes; sin ese contexto se degrada a `REVIEW`.

### Modo `SHADOW`

`SHADOW` es el valor predeterminado y el configurado en producción. Si la política propone `BLOCK`, el servicio conserva esa propuesta, registra la evaluación y devuelve `decision=REVIEW`. Por lo tanto, el modelo v4 productivo **no bloquea accesos** mientras se observa su comportamiento.

### Modo `ENFORCE`

Con `ANOMALY_MODE=ENFORCE`, la decisión efectiva coincide con la propuesta y un `BLOCK` puede activar el rechazo y el flujo de aprobación administrativa en el backend.

## Artefactos y arranque seguro

El runtime requiere:

```text
ML/models/isolation_forest_v4.pkl
ML/models/risk_policy_v4.json
```

El Dockerfile los copia a `/app/models`. El proceso valida existencia, SHA-256, tipo de política, semántica, transformación de score, contexto, umbrales, orden y cantidad de features. Una incompatibilidad impide el arranque; ya no existe fallback silencioso a reglas.

`requirements.txt` fija `scikit-learn==1.5.2`, versión compatible con el artefacto v4.

## Ejecución local

El servicio necesita PostgreSQL y `ANOMALY_DB_URL`:

```powershell
cd anomaly-detection
$env:ANOMALY_DB_URL = "postgresql://citypass:citypass@localhost:5432/login_federado"
$env:ANOMALY_MODE = "SHADOW"
python -m pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

En el entorno integrado se recomienda usar el Compose de `login-federado`.

## Testing

La CI compila los módulos y ejecuta la suite `unittest`:

```bash
python -m compileall -q app ML export_real_events.py train.py tests
python -m unittest discover -s tests -v
```

Al corte existen 62 métodos de prueba en diez archivos. La auditoría informa su ejecución aprobada con scikit-learn 1.5.2 y sin warnings de incompatibilidad.

## Métricas y limitaciones

Las métricas vigentes están en `ML/models/risk_policy_v4.json` y corresponden a calibración sintética. El FPR de `BLOCK` fue 0,895 % y el FPR combinado de `REVIEW`/`BLOCK`, 9,895 %. Las antiguas métricas P 79,15 %, R 78,20 % y F1 78,67 % pertenecen a un experimento anterior y no describen v4.

Pendientes antes de considerar `ENFORCE`:

- validar con datos reales representativos;
- definir monitoreo de deriva, revisiones y falsos positivos;
- acordar aprobación y rollback del cambio de modo;
- conservar evidencia de runs, versión y hashes desplegados.

## Documentación relacionada

- [Detalle del pipeline y métricas v4](ML/ML_README.md)
- [ADR-009](../login-federado/docs/adr/ADR-009-ia-ml-deteccion-anomalias.md)
- [Estrategia de testing](../login-federado/docs/testing-evidence.md)
- [Demo Hito 2](../login-federado/docs/demo-hito-2.md)
