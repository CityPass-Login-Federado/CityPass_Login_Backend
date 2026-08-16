# login-federado

Módulo de Login Federado (LDAP + JWT) — Proyecto CityPass+, Grupo 2.

## Stack
Java 21 · Spring Boot 3 · Spring Security 6 · OpenLDAP · PostgreSQL · JWT (RS256) · Testcontainers

## Requisitos
- JDK 21
- Maven 3.9+
- Docker + Docker Compose

## Levantar el entorno de desarrollo

```bash
# 1. Generar el par de claves RSA para firmar los JWT (una sola vez)
mkdir -p src/main/resources/keys
openssl genrsa -out src/main/resources/keys/private_key.pem 2048
openssl rsa -in src/main/resources/keys/private_key.pem -pubout -out src/main/resources/keys/public_key.pem

# 2. Levantar LDAP y PostgreSQL
docker compose up -d

# 3. Correr la app (Spring Boot detecta el docker-compose.yml automáticamente
#    si se deja spring.docker.compose.enabled=true, pero con el paso 2 alcanza)
./mvnw spring-boot:run
```

La API queda disponible en `http://localhost:8081`.
Documentación Swagger en `http://localhost:8081/docs`.

## Usuarios de prueba (sembrados en LDAP vía `ldap/seed/01-seed.ldif`)

| uid | rol | password |
|---|---|---|
| jperez | ciudadano | changeit123 |
| mgomez | empleado_municipal | changeit123 |
| admin1 | admin | changeit123 |

## Estructura de paquetes

```
config/      Configuración de Spring Security, LDAP, JWT (beans, encoders/decoders)
controller/  Endpoints REST (AuthController, etc.)
service/     Lógica de negocio (autenticación, emisión/validación de tokens)
repository/  Acceso a datos (PostgreSQL vía JPA)
model/       Entidades JPA
dto/         Objetos de request/response de la API
security/    Filtros, providers y utilidades de seguridad
event/       Publicación de eventos al bus (contrato del Grupo 1 - EDA)
exception/   Manejo centralizado de errores
```

## Tests
```bash
./mvnw test
```
Usa Testcontainers para levantar OpenLDAP y PostgreSQL reales durante los tests de integración.