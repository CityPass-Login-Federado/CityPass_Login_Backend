# ADR-009: Detección híbrida de anomalías con Isolation Forest y reglas deterministas

## Quiénes

| Nombre | Rol |
|--------|-----|
| Nicolas Hernandez| Backend |

## Consideraciones

El sistema debe identificar intentos de login potencialmente anómalos y devolver una decisión operativa para el flujo de autenticación.

Las señales disponibles incluyen:

- Hora del intento.
- Dirección IP nueva para el usuario.
- Dispositivo nuevo para el usuario.
- Cantidad de fallos recientes en una ventana de 15 minutos.
- Historial reciente de intentos del usuario.

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
| Mantiene una alternativa si el modelo no está disponible | Deben coordinarse los dos mecanismos |
| Permite evolucionar gradualmente hacia ML | Los resultados pueden diferir entre ambos modos |

## Por todo esto, definimos

Adoptar un enfoque **híbrido**:

1. Utilizar reglas deterministas como mecanismo de respaldo.
2. Utilizar `IsolationForest` cuando exista un modelo entrenado y disponible.
3. Construir las features a partir del historial de intentos del usuario.
4. Normalizar el resultado a un score entre `0` y `1`.
5. Traducir el score a tres decisiones:
   - `ALLOW` si el score es menor que `0.4`.
   - `REVIEW` si el score está entre `0.4` y `0.7`.
   - `BLOCK` si el score es igual o superior a `0.7`.
6. Devolver motivos asociados al resultado para permitir auditoría y diagnóstico.
7. Entrenar el modelo offline y cargarlo como artefacto versionado durante el despliegue.

El modelo se basará inicialmente en:

```text
hour_of_day
is_new_ip
is_new_device
recent_failures_15min
```

## Consecuencias

### Positivas

- El servicio continúa funcionando aunque el modelo no esté disponible.
- Las reglas proporcionan un comportamiento inicial y auditable.
- Isolation Forest permite detectar patrones anómalos sin disponer inicialmente de etiquetas.
- Las decisiones se expresan mediante umbrales claros.
- El modelo puede reentrenarse sin modificar el backend Java.
- La respuesta incluye motivos útiles para operaciones y seguridad.

### Negativas

- El modelo puede producir falsos positivos o falsos negativos.
- Los umbrales `0.4` y `0.7` requieren validación con datos reales.
- El score normalizado es aproximado y no representa directamente una probabilidad.
- La calidad depende de que las features de entrenamiento e inferencia sean idénticas.
- Se necesita monitorear la distribución de los datos y el rendimiento del modelo.
- El archivo del modelo debe gestionarse como artefacto de despliegue confiable.

## Referencias (benchmark)

- Isolation Forest — https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.IsolationForest.html
- scikit-learn User Guide — https://scikit-learn.org/stable/user_guide.html
- OWASP Machine Learning Security Top Ten — https://owasp.org/www-project-machine-learning-security-top-10/
- NIST AI Risk Management Framework — https://www.nist.gov/itl/ai-risk-management-framework
