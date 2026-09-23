1. Objetivo
El módulo anomaly-detection es un microservicio independiente encargado de analizar el contexto de un intento de login y asignarle un nivel de riesgo mediante reglas determinísticas.
Su objetivo es complementar las validaciones tradicionales de autenticación con señales de comportamiento, por ejemplo:
- uso de una IP que nunca fue vista para el usuario;
- uso de un dispositivo o User-Agent desconocido;
- varios intentos fallidos recientes;
- acceso en un horario considerado inusual.
El servicio no valida la contraseña ni emite tokens. La autenticación continúa siendo responsabilidad del backend login-federado.
2. Arquitectura general
Usuario
  |
  | POST /auth/login
  v
login-federado (Spring Boot)
  |
  | valida bloqueo temporal
  | valida usuario, módulo y contraseña
  |
  | POST /score
  v
anomaly-detection (FastAPI)
  |
  | consulta historial en PostgreSQL
  | construye features
  | aplica reglas
  | calcula score y decisión
  v
ALLOW / REVIEW / BLOCK
  |
  v
login-federado
El análisis se ejecuta después de que las credenciales fueron validadas correctamente en LDAP.
3. Responsabilidades
login-federado
Se encarga de:
- recibir el login;
- verificar bloqueos por fuerza bruta;
- buscar al usuario en LDAP;
- validar el módulo;
- validar la contraseña;
- consultar anomaly-detection;
- registrar el resultado del intento;
- emitir tokens si corresponde.
anomaly-detection
Se encarga de:
- recibir los datos del intento;
- consultar el historial del usuario;
- construir variables de análisis;
- calcular un risk_score;
- devolver una decisión;
- devolver los motivos que aumentaron el riesgo.
El servicio consulta PostgreSQL en modo lectura y no modifica directamente los datos de autenticación.
4. Endpoints
GET /health
Verifica que el servicio esté disponible.
{
  "status": "ok"
}
POST /score
Calcula el riesgo de un intento de login.
Ejemplo de request:
{
  "username": "jperez",
  "ip": "172.18.0.1",
  "user_agent": "Mozilla/5.0",
  "timestamp": "2026-09-23T20:00:00Z",
  "success": true
}
Ejemplo de response:
{
  "risk_score": 0.3,
  "decision": "ALLOW",
  "reasons": [
    "IP nunca vista para este usuario"
  ]
}
Las decisiones posibles son:
ALLOW
REVIEW
BLOCK
5. Construcción de features
La lógica se encuentra en:
anomaly-detection/app/features.py
El servicio consulta hasta los últimos 200 intentos del usuario:
SELECT ip_address, user_agent, attempted_at, successful
FROM login_attempts
WHERE username = :u
ORDER BY attempted_at DESC
LIMIT 200
A partir de ese historial construye:
hour_of_day
Hora del intento actual.
03:25 → 3
14:10 → 14
is_new_ip
Indica si la IP actual apareció anteriormente.
0 → IP conocida
1 → IP nueva
is_new_device
Indica si el User-Agent actual apareció anteriormente.
0 → dispositivo/User-Agent conocido
1 → dispositivo/User-Agent nuevo
recent_failures_15min
Cantidad de intentos fallidos registrados en los últimos 15 minutos.
La ventana utilizada es:
900 segundos = 15 minutos
history_size
Cantidad de registros históricos recuperados para el usuario, con un máximo de 200.
Esta variable se construye, aunque actualmente las reglas no le asignan puntos directamente.
6. Reglas de riesgo
Las reglas están en:
anomaly-detection/app/rules.py
El score comienza en:
0.0
IP nueva
Si:
is_new_ip = 1
se suma:
+0.3
Motivo:
IP nunca vista para este usuario
Dispositivo nuevo
Si:
is_new_device = 1
se suma:
+0.2
Motivo:
Dispositivo/user-agent nunca visto
Múltiples fallos recientes
Si:
recent_failures_15min >= 3
se suma:
+0.4
Motivo:
Múltiples fallos recientes
Horario inusual
La condición implementada es:
hour_of_day < 5 or hour_of_day > 23
Como la hora normal toma valores de 0 a 23, en la práctica se activa entre:
00:00 y 04:59
Se suma:
+0.1
Motivo:
Horario inusual
Límite máximo
El score nunca supera:
1.0
Ejemplo:
IP nueva                  +0.3
Dispositivo nuevo         +0.2
3 o más fallos recientes  +0.4
Horario inusual           +0.1
                          ----
Total                      1.0
7. Decisión final
ALLOW
risk_score < 0.4
Ejemplo:
IP nueva = +0.3
Score = 0.3
Decision = ALLOW
REVIEW
0.4 <= risk_score < 0.7
Ejemplo:
3 fallos recientes = +0.4
Score = 0.4
Decision = REVIEW
BLOCK
risk_score >= 0.7
Ejemplo:
IP nueva            +0.3
3 fallos recientes  +0.4
                    ----
Score                0.7
Decision             BLOCK
8. Ejemplos
Caso normal
hour_of_day = 14
is_new_ip = 0
is_new_device = 0
recent_failures_15min = 0
Resultado:
Score: 0.0
Decision: ALLOW
Reasons: []
Nueva IP
hour_of_day = 14
is_new_ip = 1
is_new_device = 0
recent_failures_15min = 0
Resultado:
Score: 0.3
Decision: ALLOW
Reasons:
- IP nunca vista para este usuario
Dispositivo nuevo y varios fallos
hour_of_day = 14
is_new_ip = 0
is_new_device = 1
recent_failures_15min = 3
Resultado:
Score: 0.6
Decision: REVIEW
IP nueva y varios fallos
hour_of_day = 14
is_new_ip = 1
is_new_device = 0
recent_failures_15min = 3
Resultado:
Score: 0.7
Decision: BLOCK
Todas las señales
hour_of_day = 2
is_new_ip = 1
is_new_device = 1
recent_failures_15min = 4
Resultado:
Score: 1.0
Decision: BLOCK
9. Integración con login-federado
La comunicación se realiza desde:
login-federado/src/main/java/citypass/loginfederado/security/AnomalyRiskClient.java
El backend envía:
username
ip
userAgent
timestamp
success
a:
POST /score
La URL se configura mediante:
anomaly:
  service:
    url: ${ANOMALY_SERVICE_URL:http://localhost:8000}
En Docker Compose se utiliza el nombre del servicio:
http://anomaly-detection:8000
10. Momento del análisis
En AuthService, el análisis ocurre después de:
1. comprobar el bloqueo temporal;
2. validar que la contraseña no esté vacía;
3. buscar al usuario;
4. validar el módulo;
5. validar las credenciales mediante LDAP.
Recién entonces se invoca el análisis de riesgo.
11. Tratamiento de las decisiones
ALLOW
El backend continúa:
registrar login exitoso
→ emitir access token
→ emitir refresh token
→ publicar evento de login
REVIEW
Actualmente REVIEW no bloquea el login.
El backend tiene pendiente persistir este estado específicamente para auditoría.
REVIEW
→ el login continúa
BLOCK
Cuando la decisión es:
BLOCK
AuthService:
1. registra un warning de seguridad;
2. registra el intento como fallido;
3. rechaza el login;
4. devuelve el mismo error genérico utilizado para otros fallos de autenticación.
Ejemplo de log:
Login rechazado por anomalías: usuario=jperez razones=[...]
Los motivos internos no se exponen al usuario final.
12. Respuesta genérica de seguridad
Aunque el rechazo sea producido por las reglas de anomalías, el cliente recibe un error genérico de autenticación.
Esto evita revelar al atacante qué control específico provocó el rechazo.
Los motivos quedan disponibles para:
logging
auditoría
diagnóstico
13. Caída del servicio
AnomalyRiskClient configura:
connect timeout: 2 segundos
read timeout:    3 segundos
Si el backend no puede consultar anomaly-detection, no interpreta automáticamente esa situación como un login autorizado.
El diseño es:
fail-closed
14. PostgreSQL
El servicio utiliza:
ANOMALY_DB_URL
para conectarse a la misma base PostgreSQL.
En Docker Compose:
postgresql://citypass:citypass@postgres:5432/login_federado
La tabla principal utilizada para construir las features es:
login_attempts
El servicio consulta el historial, pero los intentos son registrados por login-federado.
15. Relación con el bloqueo por fuerza bruta
El análisis de anomalías y el bloqueo temporal son controles diferentes.
Bloqueo por fuerza bruta
Gestionado por:
LoginAttemptService
login_lockouts
Ejemplo:
5 intentos fallidos dentro de 15 minutos
→ bloqueo temporal durante 10 minutos
Esta validación ocurre antes del análisis de anomalías.
Análisis de anomalías
Analiza:
IP nueva
dispositivo nuevo
fallos recientes
horario inusual
y devuelve:
risk_score
decision
reasons
Por lo tanto:
Login
 |
 +--> Capa 1: protección por fuerza bruta
 |
 +--> LDAP: identidad y contraseña
 |
 +--> Capa 2: análisis de anomalías basado en reglas
 |
 +--> emisión de tokens
16. Archivos principales
anomaly-detection/
└── app/
    ├── main.py
    ├── schemas.py
    ├── features.py
    └── rules.py
main.py
Define FastAPI y los endpoints:
GET /health
POST /score
schemas.py
Define los contratos de entrada y salida.
features.py
Consulta PostgreSQL y construye las variables de análisis.
rules.py
Contiene las reglas determinísticas que incrementan el score y agregan los motivos.
17. Flujo completo
POST /auth/login
       |
       v
¿Cuenta bloqueada temporalmente?
       |
       +-- Sí --> rechazar
       |
       No
       |
       v
Buscar usuario en LDAP
       |
       v
Validar módulo
       |
       v
Validar contraseña
       |
       v
POST /score
       |
       v
Consultar login_attempts
       |
       v
Construir features
       |
       +-- hour_of_day
       +-- is_new_ip
       +-- is_new_device
       +-- recent_failures_15min
       +-- history_size
       |
       v
Aplicar reglas
       |
       v
Calcular risk_score
       |
       +-- < 0.4 ----------> ALLOW
       |
       +-- 0.4 a < 0.7 ---> REVIEW
       |
       +-- >= 0.7 ---------> BLOCK
       |
       v
login-federado procesa la decisión
       |
       +-- BLOCK --> rechaza login
       |
       +-- ALLOW/REVIEW --> continúa
       |
       v
Registrar login exitoso
       |
       v
Emitir tokens
18. Conclusión
El módulo anomaly-detection agrega una segunda capa de seguridad al flujo de autenticación de CityPass.
Su funcionamiento se basa en información contextual e histórica del usuario. A partir de ese historial construye variables simples, aplica reglas explícitas y produce un score entre 0.0 y 1.0.
Cada incremento del score está acompañado por un motivo concreto, lo que permite auditar y explicar por qué un intento fue considerado de mayor riesgo.
La arquitectura mantiene separadas las responsabilidades:
Spring Boot → autenticación y autorización
LDAP        → identidad y validación de credenciales
PostgreSQL  → historial de intentos y bloqueos
FastAPI     → análisis de riesgo basado en reglas
Esto permite mantener la lógica de riesgo desacoplada del flujo principal de autenticación.