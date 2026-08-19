# ADR-007: Dockerfile multi-stage para la aplicación

## Estado: Aceptado

## Contexto

La aplicación Spring Boot necesita desplegarse como contenedor Docker. Actualmente el `docker-compose.yml` solo levanta infraestructura (LDAP + PostgreSQL), y la app se ejecuta fuera de Docker con `mvn spring-boot:run`. Se necesita un Dockerfile para:
- Despliegue consistente en todos los ambientes
- Integración con orquestadores (Docker Compose, ECS, Cloud Run)
- Imagen mínima de producción (sin herramientas de build)

## Opciones consideradas

### Opción A: Dockerfile single-stage (solo Maven)

| Pros | Contras |
|------|---------|
| Simple | Imagen grande (~700MB+) con Maven, código fuente, etc. |
| Un solo paso de build | Incluye herramientas innecesarias en producción |
| | Tiempo de build lento en cada deploy |

### Opción B: Dockerfile multi-stage (build + runtime)

| Pros | Contras |
|------|---------|
| Imagen de producción mínima (~200MB JRE) | Más complejo de escribir |
| Build reproducible (Maven en stage 1) | Dos stages que mantener |
| Sin código fuente ni herramientas de build en prod | |
| Mejor seguridad (menos superficie de ataque) | |

### Opción C: Buildpacks (Spring Boot Buildpacks)

| Pros | Contras |
|------|---------|
| No necesita Dockerfile | Menos control sobre la imagen |
| Auto-detecta el runtime | Dependencia de buildpacks |
| Genera imagen optimizada | Menos transparente |

## Decisión

**Opción B: Dockerfile multi-stage**

Elegimos multi-stage porque:
1. **Seguridad**: La imagen de producción solo tiene el JRE y el JAR — sin Maven, sin código fuente, sin shell
2. **Tamaño**: ~200MB vs ~700MB+ (single-stage)
3. **Reproducibilidad**: El build usa Maven en un contenedor estandarizado
4. **Control**: Sabemos exactamente qué hay en la imagen final
5. **Estándar**: Es la práctica recomendada para aplicaciones Java en contenedores

## Consecuencias

- Se necesita un `Dockerfile` con dos stages: `build` (Maven) y `runtime` (JRE)
- El `.dockerignore` excluye `target/`, `.git/`, `keys/`, etc.
- El `docker-compose.yml` se extiende con un servicio `app` que usa la imagen local
- En CI/CD, el build de la imagen se ejecuta como paso del pipeline
