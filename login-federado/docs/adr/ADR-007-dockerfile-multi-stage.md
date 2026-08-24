# ADR-007: Dockerfile multi-stage para la aplicación

## Quiénes

| Nombre | Rol |
|--------|-----|
| Antonio Wu | Seguridad / Backend |

## Consideraciones

La aplicación Spring Boot necesita desplegarse como contenedor Docker. Actualmente el `docker-compose.yml` solo levanta infraestructura (LDAP + PostgreSQL), y la app se ejecuta fuera de Docker con `mvn spring-boot:run`. Se necesita un Dockerfile para:
- Despliegue consistente en todos los ambientes
- Integración con orquestadores (Docker Compose, ECS, Cloud Run)
- Imagen mínima de producción (sin herramientas de build)

Restricciones y supuestos adicionales:
- El build debe ser reproducible: no depender del entorno local de cada desarrollador
- La imagen final no debe contener código fuente, cachés de Maven ni secretos
- Debe funcionar igual en CI/CD que en local

### Opciones consideradas

#### Opción A: Dockerfile single-stage (solo Maven)

| Pros | Contras |
|------|---------|
| Simple | Imagen grande (~700MB+) con Maven, código fuente, etc. |
| Un solo paso de build | Incluye herramientas innecesarias en producción |
| | Tiempo de build lento en cada deploy |
| | Mayor superficie de ataque en producción |

#### Opción B: Dockerfile multi-stage (build + runtime)

| Pros | Contras |
|------|---------|
| Imagen de producción mínima (~200MB JRE) | Más complejo de escribir |
| Build reproducible (Maven en stage 1) | Dos stages que mantener |
| Sin código fuente ni herramientas de build en prod | |
| Mejor seguridad (menos superficie de ataque) | |

#### Opción C: Buildpacks (Spring Boot Buildpacks)

| Pros | Contras |
|------|---------|
| No necesita Dockerfile | Menos control sobre la imagen |
| Auto-detecta el runtime | Dependencia de buildpacks |
| Genera imagen optimizada | Menos transparente |

## Por todo esto, definimos

Adoptar un **Dockerfile multi-stage**: stage `build` (imagen Maven para compilar y empaquetar) y stage `runtime` (imagen JRE mínima con solo el JAR).

Razones principales:
1. **Seguridad**: La imagen de producción solo tiene el JRE y el JAR — sin Maven, sin código fuente, sin shell
2. **Tamaño**: ~200MB vs ~700MB+ (single-stage)
3. **Reproducibilidad**: El build usa Maven en un contenedor estandarizado
4. **Control**: Sabemos exactamente qué hay en la imagen final
5. **Estándar**: Es la práctica recomendada por Docker y Spring Boot para aplicaciones Java

## Consecuencias

### Positivas

- Imagen de producción mínima y sin artefactos de build ni secretos
- Misma imagen ejecutable en local, CI/CD y producción
- Superficie de ataque reducida (menos paquetes = menos CVEs)
- Caché de capas Docker acelera rebuilds cuando solo cambia el código

### Negativas

- Dockerfile más complejo de escribir y mantener (dos stages)
- Primer build más lento (descarga de imágenes base y dependencias)
- Requiere `.dockerignore` bien configurado para excluir `target/`, `.git/`, `keys/`, etc.
- CI/CD debe construir y publicar la imagen como paso explícito del pipeline

## Referencias (benchmark)

- Docker Docs — Multi-stage builds — https://docs.docker.com/build/building/multi-stage/
- Spring Boot Reference — Container Images — https://docs.spring.io/spring-boot/reference/packaging/container-images.html
- Eclipse Temurin (imágenes oficiales de JDK/JRE) — https://hub.docker.com/_/eclipse-temurin
- Google Distroless Images (benchmark de imágenes mínimas) — https://github.com/GoogleContainerTools/distroless
