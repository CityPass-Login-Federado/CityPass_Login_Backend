# =============================================================================
# Stage 1: Build — instalar dependencias en un venv aislado
# =============================================================================
FROM python:3.12-slim AS build

WORKDIR /app

# Copiar solo requirements primero para cachear la instalación de dependencias
COPY requirements.txt .
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
RUN pip install --no-cache-dir -r requirements.txt

# =============================================================================
# Stage 2: Runtime — imagen mínima, sin herramientas de build
# =============================================================================
FROM python:3.12-slim AS runtime

# Crear usuario no-root para seguridad
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

# Copiar el venv ya armado desde el stage de build
COPY --from=build /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Copiar código de la app y el modelo entrenado (si existe)
COPY app ./app
COPY models ./models

# Directorio de modelos debe ser escribible por si se reentrena en runtime
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8000

# Health check básico
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

# Ejecutar con uvicorn
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]