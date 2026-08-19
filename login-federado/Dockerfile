# =============================================================================
# Stage 1: Build — Maven + JDK 21
# =============================================================================
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Copiar pom.xml primero para cachear descarga de dependencias
COPY pom.xml .
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B

# Copiar código fuente y compilar
COPY src ./src
RUN mvn package -DskipTests -B

# =============================================================================
# Stage 2: Runtime — Solo JRE (imagen mínima)
# =============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Crear usuario no-root para seguridad
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copiar el JAR desde el stage de build
COPY --from=build /app/target/*.jar app.jar

# Crear directorio para claves RSA (se generan automáticamente en dev)
RUN mkdir -p /app/keys && chown appuser:appgroup /app/keys

# Cambiar a usuario no-root
USER appuser

# Puerto de la aplicación
EXPOSE 8081

# Variables de entorno con defaults para desarrollo
ENV LDAP_ADMIN_PASSWORD=admin
ENV DB_USERNAME=citypass
ENV DB_PASSWORD=citypass

# Health check básico
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health || exit 1

# Ejecutar la aplicación
ENTRYPOINT ["java", "-jar", "app.jar"]
