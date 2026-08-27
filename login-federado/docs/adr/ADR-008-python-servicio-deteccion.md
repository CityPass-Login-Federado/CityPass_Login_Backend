# ADR-008: Python para el servicio de detección de anomalías

## Quiénes

| Nombre | Rol |
|--------|-----|
| Nicolas Hernandez| Backend |

## Consideraciones

CityPass+ necesita un servicio independiente para calcular el riesgo de los intentos de login mediante reglas e inteligencia artificial.

Restricciones y supuestos:

- El servicio de detección debe poder escalar horizontalmente.
- Debe integrarse con el backend Java mediante HTTP/JSON.
- El procesamiento de datos y los modelos disponibles tienen un ecosistema especialmente maduro en Python.
- El servicio necesita consultar el historial de intentos de login.
- No debe modificar directamente la base de datos de autenticación.
- Las dependencias deben quedar versionadas y reproducibles.

### Opciones consideradas

#### Opción A: Implementar la detección dentro del backend Java

| Pros | Contras |
|------|---------|
| Menos servicios desplegados | Menor disponibilidad de librerías de IA/ML |
| Comunicación interna directa | Mezcla autenticación y análisis de riesgo |
| Reutiliza la infraestructura existente | Dificulta experimentar y versionar modelos |

#### Opción B: Servicio independiente en Python

| Pros | Contras |
|------|---------|
| Ecosistema amplio para análisis de datos y ML | Requiere desplegar un servicio adicional |
| Separación clara de responsabilidades | Necesita contrato HTTP entre servicios |
| Permite evolucionar los modelos independientemente | Requiere controlar versiones de dependencias |
| Facilita pruebas y entrenamiento offline | Añade una llamada de red al flujo de login |

#### Opción C: Plataforma externa de IA/ML

| Pros | Contras |
|------|---------|
| Menor mantenimiento de infraestructura ML | Dependencia de un proveedor externo |
| Puede ofrecer modelos administrados | Costes operativos variables |
| Escalabilidad administrada | Riesgos de privacidad y latencia |
| | Menor control sobre los datos y el modelo |

## Por todo esto, definimos

Adoptar **Python con FastAPI** para implementar el servicio independiente de detección de anomalías.

El servicio expondrá:

- `GET /health` para verificar disponibilidad.
- `POST /score` para calcular el riesgo de un intento de login.
- Respuestas JSON con score, decisión y motivos.
- Acceso de solo lectura al historial de intentos de login.

Las dependencias Python se fijarán explícitamente en `requirements.txt`. El servicio no mantendrá sesiones ni estado de autenticación en memoria.

## Consecuencias

### Positivas

- Separación entre autenticación y análisis de riesgo.
- Uso de librerías maduras como pandas y scikit-learn.
- Escalado y despliegue independientes del backend Java.
- Posibilidad de actualizar el modelo sin modificar el contrato de login.
- Mayor facilidad para realizar experimentos y entrenamientos offline.

### Negativas

- Se incorpora un servicio adicional al sistema.
- El flujo de login depende de una comunicación HTTP adicional.
- Deben gestionarse timeouts, errores y disponibilidad del servicio.
- El equipo debe mantener dos ecosistemas tecnológicos.
- Las versiones de Python y sus dependencias deben controlarse cuidadosamente.

## Referencias (benchmark)

- FastAPI — https://fastapi.tiangolo.com/
- Python — https://www.python.org/
- scikit-learn — https://scikit-learn.org/
- Twelve-Factor App — https://12factor.net/
- [Servicio de detección](../../anomaly-detection/app/main.py)
- [Dependencias Python](../../anomaly-detection/requirements.txt)