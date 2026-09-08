#!/usr/bin/env bash
# Muestrea `docker stats` durante un tiempo fijo y reporta el máximo de RAM
# y CPU usado por cada contenedor de un proyecto de docker compose,
# agregando el resultado como una tabla markdown a $GITHUB_STEP_SUMMARY.
#
# `docker stats` solo da una foto del instante en que se lo llama, no un
# máximo histórico — por eso este script lo llama repetidas veces durante
# DURATION segundos y se queda con el mayor valor visto por contenedor.
#
# Uso:
#   resource-report.sh <etiqueta-VM> <compose-project> <duracion-seg> <intervalo-seg> [servicio-a-excluir-del-total ...]
#
# Ejemplo:
#   resource-report.sh "VM1 (login-backend)" login-federado 45 3 postgres

set -euo pipefail

VM_LABEL="$1"; shift
PROJECT="$1"; shift
DURATION="$1"; shift
INTERVAL="$1"; shift
EXCLUDE=("$@")   # nombres de *servicio* a excluir del total de RAM —
                  # p.ej. el postgres que solo existe para el smoke test,
                  # no corre en la VM real.
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  SUMMARY_FILE="$GITHUB_STEP_SUMMARY"
else
  SUMMARY_FILE="/tmp/resource-report-${PROJECT}.md"
  rm -f "$SUMMARY_FILE"
fi

if ! docker info >/dev/null 2>&1; then
  {
    echo "### Recursos máximos — ${VM_LABEL}"
    echo ""
    echo "⚠️ Docker no está disponible o el daemon no está iniciado en este runner."
    echo ""
  } >> "$SUMMARY_FILE"
  if [ -z "${GITHUB_STEP_SUMMARY:-}" ]; then
    cat "$SUMMARY_FILE"
  fi
  exit 0
fi

is_excluded() {
  local svc="$1"
  for e in "${EXCLUDE[@]:-}"; do
    [ "$svc" = "$e" ] && return 0
  done
  return 1
}

# Convierte un valor de docker stats ("45.3MiB", "1.2GiB", "512KiB", "900B")
# a MiB. Usa awk en vez de `bc` porque no siempre está instalado.
to_mib() {
  echo "$1" | awk '
    /GiB/ { gsub("GiB",""); printf "%f", $1*1024; next }
    /MiB/ { gsub("MiB",""); printf "%f", $1; next }
    /KiB/ { gsub("KiB",""); printf "%f", $1/1024; next }
    { gsub("[A-Za-z]",""); printf "%f", $1/1024/1024 }
  '
}

mapfile -t CONTAINER_IDS < <(
  docker ps --filter "label=com.docker.compose.project=${PROJECT}" --format '{{.ID}}'
)
if [ "${#CONTAINER_IDS[@]}" -eq 0 ]; then
  {
    echo "### Recursos máximos — ${VM_LABEL}"
    echo ""
    echo "⚠️ No se encontraron contenedores en ejecución para el proyecto Compose \\`${PROJECT}\\`."
    echo ""
  } >> "$SUMMARY_FILE"
  echo "No se encontraron contenedores para el proyecto '${PROJECT}'." >&2
  if [ -z "${GITHUB_STEP_SUMMARY:-}" ]; then
    cat "$SUMMARY_FILE"
  fi
  exit 0
fi

echo "Muestreando ${#CONTAINER_IDS[@]} contenedor(es) del proyecto '${PROJECT}': ${CONTAINER_IDS[*]}" >&2

declare -A MAX_MIB
declare -A MAX_CPU

END=$((SECONDS + DURATION))
while [ "$SECONDS" -lt "$END" ]; do
  while IFS=';' read -r NAME CPU MEM; do
    [ -z "$NAME" ] && continue

    CPU_NUM=$(echo "$CPU" | tr -d '%')
    MEM_USED=$(echo "$MEM" | cut -d'/' -f1 | xargs)
    MIB=$(to_mib "$MEM_USED")

    CUR_MEM="${MAX_MIB[$NAME]:-0}"
    GREATER_MEM=$(awk -v a="$MIB" -v b="$CUR_MEM" 'BEGIN { print (a>b) ? 1 : 0 }')
    [ "$GREATER_MEM" -eq 1 ] && MAX_MIB["$NAME"]="$MIB"

    CUR_CPU="${MAX_CPU[$NAME]:-0}"
    GREATER_CPU=$(awk -v a="$CPU_NUM" -v b="$CUR_CPU" 'BEGIN { print (a>b) ? 1 : 0 }')
    [ "$GREATER_CPU" -eq 1 ] && MAX_CPU["$NAME"]="$CPU_NUM"
  done < <(docker stats --no-stream --format '{{.Name}};{{.CPUPerc}};{{.MemUsage}}' "${CONTAINER_IDS[@]}" 2>/dev/null || true)
  sleep "$INTERVAL"
done

{
  echo "### 📊 Recursos máximos — ${VM_LABEL}"
  echo ""
  echo "| Contenedor | RAM máxima | CPU máxima* |"
  echo "|---|---|---|"
  TOTAL_MEM=0
  if [ "${#MAX_MIB[@]}" -eq 0 ]; then
    echo "| _Sin muestras de docker stats_ | _N/D_ | _N/D_ |"
  fi
  for NAME in "${!MAX_MIB[@]}"; do
    M="${MAX_MIB[$NAME]:-0}"
    C="${MAX_CPU[$NAME]:-0}"
    SVC=$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "$NAME" 2>/dev/null || echo "$NAME")
    if is_excluded "$SVC"; then
      printf "| \`%s\` _(solo smoke test, no corre en la VM real)_ | %.1f MiB | %.1f%% |\n" "$NAME" "$M" "$C"
    else
      printf "| \`%s\` | %.1f MiB | %.1f%% |\n" "$NAME" "$M" "$C"
      TOTAL_MEM=$(awk -v t="$TOTAL_MEM" -v m="$M" 'BEGIN { printf "%f", t+m }')
    fi
  done
  printf "| **Total RAM (solo servicios reales de la VM)** | **%.1f MiB** | |\n" "$TOTAL_MEM"
  echo ""
  OVER=$(awk -v t="$TOTAL_MEM" 'BEGIN { print (t>1024) ? 1 : 0 }')
  if [ "$OVER" -eq 1 ]; then
    echo "⚠️ **Supera el presupuesto de 1 GB de la VM real (${VM_LABEL}).**"
  else
    echo "✅ RAM dentro del presupuesto de 1 GB de la VM real (${VM_LABEL})."
  fi
  echo ""
  echo "_* El % de CPU es relativo a los núcleos del runner de GitHub Actions"
  echo "(no a los 1/8 OCPU reales de la VM) — sirve para comparar entre"
  echo "corridas, no como predicción absoluta de cómo se va a comportar en"
  echo "la VM real._"
  echo ""
} >> "$SUMMARY_FILE"

if [ -z "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat "$SUMMARY_FILE"
fi