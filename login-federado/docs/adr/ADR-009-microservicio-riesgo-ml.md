# ADR-009: Microservicio Python desacoplado para detección de anomalías

## Estado

Accepted — 14 de noviembre de 2026, con implementación ML incompleta.

## Contexto y Problema

El umbral fijo de intentos detecta fuerza bruta directa pero no cambios de IP, dispositivo u horario. Se busca experimentar con detección no supervisada sin introducir librerías Python en el proceso Java ni acoplar el login al formato interno del modelo.

## Opciones Consideradas (Pros y Contras de cada una)

### Reglas dentro de Spring Boot

- Pros: un proceso y menor latencia.
- Contras: mezcla responsabilidades y limita el ecosistema ML.

### Modelo embebido/exportado a Java

- Pros: inferencia local y sin dependencia HTTP.
- Contras: conversión/compatibilidad del modelo y pipeline de entrenamiento más complejo.

### Servicio FastAPI con Isolation Forest

- Pros: aislamiento tecnológico, scikit-learn nativo, despliegue y evolución independiente.
- Contras: llamada remota en el camino crítico, otra imagen y dependencia compartida de PostgreSQL.

## Decisión Tomada y Justificación

Crear `anomaly-detection`, con `POST /score` y `GET /health`. Calcula `hour_of_day`, `is_new_ip`, `is_new_device` y `recent_failures_15min` usando los últimos 200 intentos. Si existe `models/isolation_forest.pkl`, `model.py` aplica un Isolation Forest; en su ausencia usa reglas con los mismos datos y umbrales ALLOW/REVIEW/BLOCK.

El cliente Java usa timeouts de conexión/lectura de 2/3 segundos y falla cerrado.

## Consecuencias Técnicas

- El login depende de FastAPI y PostgreSQL aun cuando use reglas.
- El scoring ocurre después de autenticar correctamente contra LDAP.
- El contenedor actual no contiene un `.pkl` versionado; por tanto, no ejecuta Isolation Forest en el estado del repositorio.
- `train.py` referencia `X` sin construirlo: falta pipeline reproducible, dataset, evaluación, versionado y promoción del modelo.
- `REVIEW` no se persiste; existe un TODO para una columna `flagged_for_review`.
- `ANOMALY_DB_URL` usa actualmente las mismas credenciales de aplicación pese al comentario que pretende solo lectura.
