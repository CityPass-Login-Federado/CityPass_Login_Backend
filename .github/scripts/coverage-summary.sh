#!/usr/bin/env bash
set -euo pipefail

CSV="target/site/jacoco/jacoco.csv"

if [ ! -f "$CSV" ]; then
  echo "⚠️ No se encontró el reporte de JaCoCo (${CSV})" >> "$GITHUB_STEP_SUMMARY"
  exit 0
fi

{
  echo "### 📊 Cobertura de código (JaCoCo)"
  echo ""
  echo "| Métrica | Cobertura |"
  echo "|---|---|"
} >> "$GITHUB_STEP_SUMMARY"

awk -F"," '
  NR > 1 {
    missed_i += $4; covered_i += $5
    missed_b += $6; covered_b += $7
    missed_l += $8; covered_l += $9
  }
  END {
    if (covered_i + missed_i > 0)
      printf "| Instrucciones | %.1f%% |\n", 100 * covered_i / (covered_i + missed_i)
    if (covered_b + missed_b > 0)
      printf "| Ramas | %.1f%% |\n", 100 * covered_b / (covered_b + missed_b)
    if (covered_l + missed_l > 0)
      printf "| Líneas | %.1f%% |\n", 100 * covered_l / (covered_l + missed_l)
  }
' "$CSV" >> "$GITHUB_STEP_SUMMARY"
