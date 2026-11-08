#!/usr/bin/env bash
# =====================================================================
# Mejora continua v4 (k6): corre escenarios de k6 contra el ambiente descartable ya levantado
# (tests/load/ambiente/levantar.sh). k6 corre como contenedor DENTRO de la red interna del compose.
#
#   tests/load/correr.sh humo pos-concurrencia idempotencia
#
# Deja en tests/load/resultados/<escenario>/ SOLO k6-summary.json e informe.md (handleSummary); al informe se le
# agregan las metricas agregadas del backend (Prometheus) y el pico de docker stats. Nada de logs ni tokens.
# Sale con codigo != 0 si algun escenario no pasa sus umbrales.
# =====================================================================
set -uo pipefail
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$RAIZ"
GEN="tests/load/.generado"
DC=(docker compose -f docker-compose.load.yml --env-file "$GEN/load.env")
RES="tests/load/resultados"
COMMIT="${COMMIT:-$(git rev-parse --short HEAD 2>/dev/null || echo local)}"

metricas() { # resumen de Prometheus del backend: solo series agregadas (pool, heap, hilos, GC, 5xx y 429)
    "${DC[@]}" exec -T postgres wget -qO- http://backend:8080/actuator/prometheus 2>/dev/null \
        | grep -E '^(hikaricp_connections(_active|_pending|_max|_timeout_total|_acquire_seconds_max)?\{|jvm_memory_used_bytes\{.*area="heap"|jvm_memory_max_bytes\{.*area="heap"|jvm_threads_live_threads|jvm_gc_pause_seconds_(count|max)|process_cpu_usage|http_server_requests_seconds_count\{.*status="(5..|429)")' \
        | head -60
}

falla=0
for esc in "$@"; do
    [ -f "tests/load/$esc.js" ] || { echo "no existe tests/load/$esc.js"; falla=1; continue; }
    mkdir -p "$RES/$esc"; chmod 777 "$RES/$esc"
    echo "== k6: $esc (commit $COMMIT)"
    # docker stats en paralelo durante la corrida (CPU/memoria por contenedor)
    ( while true; do docker stats --no-stream --format '{{.Name}};{{.CPUPerc}};{{.MemUsage}}' 2>/dev/null \
        | grep belopia-load; sleep 5; done ) > "$RES/$esc/.stats.csv" &
    STATS=$!
    "${DC[@]}" --profile k6 run --rm -T -e COMMIT="$COMMIT" k6 run --quiet "$esc.js"
    rc=$?
    kill "$STATS" 2>/dev/null; wait "$STATS" 2>/dev/null
    # informe.md es del usuario del contenedor de k6: se arma uno nuevo y se reemplaza (la carpeta es 777)
    {
        cat "$RES/$esc/informe.md" 2>/dev/null
        echo
        echo "## Servidor durante $esc"
        echo
        echo "### Pico de docker stats (CPU %, memoria) por contenedor"
        echo '| Contenedor | CPU max | Memoria al pico de CPU |'
        echo '|---|---|---|'
        sort -t';' -k2 -g -r "$RES/$esc/.stats.csv" 2>/dev/null | sed 's/%//' | awk -F';' '!vistos[$1]++ {print "| "$1" | "$2" % | "$3" |"}'
        echo
        echo "### Prometheus del backend al terminar (pool Hikari, heap, hilos, GC, 5xx y 429)"
        echo '```'
        metricas
        echo '```'
    } > "$RES/$esc/.informe.nuevo"
    mv -f "$RES/$esc/.informe.nuevo" "$RES/$esc/informe.md"
    rm -f "$RES/$esc/.stats.csv"
    [ $rc -eq 0 ] || { echo "   $esc: umbrales o checks fallaron (rc=$rc)"; falla=1; }
done
exit $falla
