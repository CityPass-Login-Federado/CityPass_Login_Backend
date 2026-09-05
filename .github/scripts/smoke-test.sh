#!/bin/bash
set -e

# Archivo temporal para captura de métricas de RAM/CPU
STATS_LOG=$(mktemp)

echo "=== 1. Levantar VM2 simulada (anomaly-detection) ==="
docker compose -f ../anomaly-detection/docker-compose.prod.yml -f ../anomaly-detection/docker-compose.smoke.yml up -d --wait

echo "=== 2. Levantar VM1 simulada (LDAP + Spring) ==="
docker compose -f docker-compose.prod.yml -f docker-compose.smoke.yml up -d --wait

echo "=== 3. Iniciando Monitoreo de RAM en Segundo Plano ==="
(
  while true; do
    docker stats --no-stream --format "{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}" >> "$STATS_LOG" 2>/dev/null || true
    sleep 0.5
  done
) &
MONITOR_PID=$!

# Asegurar que mate el proceso en segundo plano al salir
trap "kill $MONITOR_PID 2>/dev/null || true" EXIT

echo "=== 4. Midiendo Latencia de Endpoints ==="
LATENCY_BACKEND=""
LATENCY_ANOMALY=""

check_latency() {
  local name=$1
  local url=$2
  local response=$(curl -o /dev/null -s -w "%{http_code}\t%{time_total}s" --retry 10 --retry-delay 2 "$url")
  local code=$(echo "$response" | cut -f1)
  local time=$(echo "$response" | cut -f2)

  if [ "$code" -eq 200 ]; then
    echo "  [OK] $name ($url) - Status: $code - Latencia: $time"
    eval "$3='$time'"
  else
    echo "  [FAIL] $name ($url) - Status: $code - Latencia: $time"
    exit 1
  fi
}

check_latency "Backend Health (VM1)" "http://localhost:8081/actuator/health" LATENCY_BACKEND
check_latency "Anomaly Detection Health (VM2)" "http://localhost:8000/health" LATENCY_ANOMALY

echo "=== 5. Deteniendo Monitoreo y Mostrando Métricas ==="
kill $MONITOR_PID 2>/dev/null || true

# Imprimir en la Consola (Logs tradicionales)
echo ""
echo "--- CONSUMO DE RAM ACTUAL ---"
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}"

echo ""
echo "--- MÁXIMO CONSUMO DE RAM REGISTRADO (PICO MÁXIMO) ---"
awk -F'\t' '{
  split($2, mem, " / ");
  print $1 " -> RAM Usada/Pico: " mem[1] " (CPU: " $3 ")";
}' "$STATS_LOG" | sort -u -k1,1

# Imprimir en el Resumen del Pipeline (Job Summary de GitHub Actions)
if [ -n "$GITHUB_STEP_SUMMARY" ]; then
  {
    echo "### 📊 Resultados de Performance y Salud del Smoke Test"
    echo ""
    echo "#### ⏱️ Latencia de Endpoints"
    echo "- **Backend Health (VM1):** \`200 OK\` (${LATENCY_BACKEND})"
    echo "- **Anomaly Detection Health (VM2):** \`200 OK\` (${LATENCY_ANOMALY})"
    echo ""
    echo "#### 💾 Consumo de Memoria RAM"
    echo "| Contenedor | RAM Usada / Pico | CPU % |"
    echo "| :--- | :--- | :--- |"
    awk -F'\t' '{
      split($2, mem, " / ");
      print "| " $1 " | " mem[1] " | " $3 " |";
    }' "$STATS_LOG" | sort -u -k1,1
  } >> "$GITHUB_STEP_SUMMARY"
fi