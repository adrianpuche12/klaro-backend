#!/usr/bin/env bash
# =====================================================================
# Mejora continua v4 (k6): corre escenarios de k6 contra el ambiente descartable ya levantado
# (tests/load/ambiente/levantar.sh). k6 corre como contenedor DENTRO de la red interna del compose.
#
#   tests/load/correr.sh humo pos-concurrencia idempotencia
#
# Deja en tests/load/resultados/<escenario>/ SOLO k6-summary.json e informe.md (handleSummary); al informe se le
# agregan las metricas agregadas del backend (Prometheus) y el pico de docker stats. Nada de logs ni tokens.
# Sale con codigo != 0 si algun escenario no pasa sus umbrales (o si su informe marca un **DESCUADRE**).
# rate-limit es el unico que corre con los limites de PROD (300 / 100 / 1000): antes se reinicia el backend con esos
# valores y despues se lo vuelve a los altos de los escenarios de capacidad. El reinicio tambien deja los buckets en cero.
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

sano() { # espera el health del backend desde la red interna (hasta ~3 min)
    for _ in $(seq 1 45); do
        "${DC[@]}" exec -T postgres wget -qO- http://backend:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"' && return 0
        sleep 4
    done
    return 1
}

limites() { # prod | alto: recrea SOLO el backend del compose descartable con esos limites de pedidos
    if [ "$1" = prod ]; then
        export LOAD_RATE_LIMIT_USER_PER_MIN=300 LOAD_RATE_LIMIT_ANON_IP_PER_MIN=100 LOAD_RATE_LIMIT_IP_CEILING_PER_MIN=1000
    else
        unset LOAD_RATE_LIMIT_USER_PER_MIN LOAD_RATE_LIMIT_ANON_IP_PER_MIN LOAD_RATE_LIMIT_IP_CEILING_PER_MIN
    fi
    echo "== backend con limites de pedidos: $1"
    "${DC[@]}" up -d --no-build --no-deps --force-recreate backend >/dev/null 2>&1
    sano || { echo "   el backend no volvio sano con limites $1"; return 1; }
    "${DC[@]}" exec -T backend env | grep -E '^RATE_LIMIT_' | sed 's/^/   /'
}

hikari() { # una muestra cada 5 s: espera max del pool (s), pendientes, timeouts acumulados, heap usado (bytes), CPU del proceso (0-1)
    while true; do
        "${DC[@]}" exec -T postgres wget -qO- http://backend:8080/actuator/prometheus 2>/dev/null | awk '/^hikaricp_connections_acquire_seconds_max\{/ {a=$2} /^hikaricp_connections_pending\{/ {p=$2}
                 /^hikaricp_connections_timeout_total\{/ {t=$2} /^jvm_memory_used_bytes\{.*area="heap"/ {h+=$NF} /^process_cpu_usage\{/ {c=$2} END {if (a != "") print a";"p";"t";"h";"c}'
        sleep 5
    done
}

falla=0
for esc in "$@"; do
    [ -f "tests/load/$esc.js" ] || { echo "no existe tests/load/$esc.js"; falla=1; continue; }
    mkdir -p "$RES/$esc"; chmod 777 "$RES/$esc"
    if [ "$esc" = rate-limit ]; then limites prod || { falla=1; continue; }; fi
    echo "== k6: $esc (commit $COMMIT)"
    # docker stats en paralelo durante la corrida (CPU/memoria por contenedor)
    ( while true; do docker stats --no-stream --format '{{.Name}};{{.CPUPerc}};{{.MemUsage}}' 2>/dev/null \
        | grep belopia-load; sleep 5; done ) > "$RES/$esc/.stats.csv" &
    STATS=$!
    hikari > "$RES/$esc/.hikari.csv" &
    HIKARI=$!
    "${DC[@]}" --profile k6 run --rm -T -e COMMIT="$COMMIT" k6 run --quiet "$esc.js"
    rc=$?
    kill "$STATS" "$HIKARI" 2>/dev/null; wait "$STATS" "$HIKARI" 2>/dev/null
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
        echo "### Pool Hikari, heap y CPU del backend durante la corrida (una muestra cada 5 s)"
        awk -F';' '{ if ($1+0 > a) a = $1+0; if ($2+0 > p) p = $2+0; t = $3; if ($4+0 > h) h = $4+0; if ($5+0 > c) c = $5+0 } END { printf "- Espera maxima para conseguir conexion: %d ms\n- Pedidos esperando conexion (max): %d\n- Timeouts del pool (acumulado): %d\n- Heap usado (max): %d MiB\n- CPU del proceso (max, sobre el total de la maquina): %d %%\n- Muestras: %d\n", a * 1000, p, t, h / 1048576, c * 100, NR }' "$RES/$esc/.hikari.csv" 2>/dev/null
        echo
        echo "### Prometheus del backend al terminar (pool Hikari, heap, hilos, GC, 5xx y 429)"
        echo '```'
        metricas
        echo '```'
    } > "$RES/$esc/.informe.nuevo"
    mv -f "$RES/$esc/.informe.nuevo" "$RES/$esc/informe.md"
    rm -f "$RES/$esc/.stats.csv" "$RES/$esc/.hikari.csv"
    if grep -q 'DESCUADRE' "$RES/$esc/informe.md"; then echo "   $esc: el cruce cliente/servidor no cuadra"; rc=1; fi
    [ $rc -eq 0 ] || { echo "   $esc: umbrales o checks fallaron (rc=$rc)"; falla=1; }
    if [ "$esc" = rate-limit ]; then limites alto || falla=1; fi
done
exit $falla
