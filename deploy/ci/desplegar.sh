#!/usr/bin/env bash
# Deploy reversible del backend de Belopia en Contabo (servidor COMPARTIDO con PH v2 y otros).
# Lo copia y lo corre el workflow (.github/workflows/deploy.yml) dentro de /opt/belopia/<amb>.
#
#   desplegar.sh <amb> <imagen-nueva> <puerto-health>
#     amb            dev | prod
#     imagen-nueva   adrianpuche/belopia-backend:sha-<7>  (la del commit)
#     puerto-health  8111 (dev) | 8110 (prod): el puerto del host que publica el compose
#
# 1. Anota la imagen que corre ahora (por ID: sirve aunque ya no tenga etiqueta).
# 2. Baja la nueva, la etiqueta como la que usa el compose (dev-latest / latest) y levanta.
# 3. Espera /actuator/health = UP hasta HEALTH_TIMEOUT s (90 por defecto).
# 4. Si no levanta: vuelve a la imagen anterior, verifica que esa sí responda, y sale con 1
#    (el job queda en rojo y GitHub avisa por mail).
# 5. Si levanta: conserva las 3 últimas imágenes de este ambiente (<amb>-sha-*) y borra solo
#    imágenes de Belopia sin etiqueta. Nunca `docker image prune` global: el servidor es compartido.
#
# Después de un rollback NO hacer `docker compose pull`: la etiqueta del registro apunta a la
# imagen rota. Se arregla con un commit nuevo (o, a mano, con otra corrida de este script).
set -euo pipefail

AMB="${1:?amb (dev|prod)}"
NUEVA="${2:?imagen nueva}"
PUERTO="${3:?puerto del health}"
TIMEOUT="${HEALTH_TIMEOUT:-90}"
REPO="adrianpuche/belopia-backend"
CONSERVAR=3

case "$AMB" in
  dev)  CONTENEDOR=belopia-backend-dev;  ETIQ_COMPOSE="$REPO:dev-latest" ;;
  prod) CONTENEDOR=belopia-backend-prod; ETIQ_COMPOSE="$REPO:latest" ;;
  *) echo "Ambiente inválido: $AMB"; exit 2 ;;
esac
[ "$(basename "$PWD")" = "$AMB" ] || { echo "Correr dentro de /opt/belopia/$AMB"; exit 2; }

log() { echo "[desplegar $AMB] $*"; }

sano() {
  local fin=$(( $(date +%s) + TIMEOUT ))
  while [ "$(date +%s)" -lt "$fin" ]; do
    if curl -fsS --max-time 5 "http://127.0.0.1:$PUERTO/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 3
  done
  return 1
}

ANTERIOR="$(docker inspect -f '{{.Image}}' "$CONTENEDOR" 2>/dev/null || true)"
log "corriendo ahora: ${ANTERIOR:-ninguna}"

# Si no está en el registro (prueba manual con una imagen local), alcanza con que exista acá.
docker pull -q "$NUEVA" 2>/dev/null || docker image inspect "$NUEVA" >/dev/null
SHA_TAG="${NUEVA##*:}"                      # sha-xxxxxxx
docker tag "$NUEVA" "$REPO:$AMB-$SHA_TAG"   # copia local por ambiente (dev y prod comparten el host)
docker tag "$NUEVA" "$ETIQ_COMPOSE"
docker compose up -d --no-deps "$CONTENEDOR"

if sano; then
  log "OK: $NUEVA responde UP"
else
  log "ERROR: $NUEVA no respondió UP en ${TIMEOUT}s. Últimas líneas del log:"
  docker logs --tail 40 "$CONTENEDOR" 2>&1 | sed 's/^/    /' || true
  if [ -z "$ANTERIOR" ]; then
    log "No había imagen anterior para volver."; exit 1
  fi
  log "Rollback a $ANTERIOR"
  docker tag "$ANTERIOR" "$ETIQ_COMPOSE"
  docker compose up -d --no-deps "$CONTENEDOR"
  if sano; then log "Rollback OK: la versión anterior responde UP"; else log "El rollback TAMPOCO responde: revisar a mano"; fi
  exit 1
fi

# Limpieza acotada a Belopia y a este ambiente.
if [[ "$NUEVA" == "$REPO:sha-"* ]]; then
  docker rmi "$NUEVA" >/dev/null 2>&1 || true   # quita solo la etiqueta del pull; queda <amb>-sha-*
fi
docker images "$REPO" --format '{{.CreatedAt}}|{{.Tag}}' \
  | grep "|$AMB-sha-" | sort -r | tail -n +$((CONSERVAR + 1)) | cut -d'|' -f2 \
  | while read -r t; do log "borro etiqueta vieja $t"; docker rmi "$REPO:$t" >/dev/null 2>&1 || true; done
# Sin etiqueta una imagen pierde el nombre del repo: se reconoce por la etiqueta OCI que pone el
# build (deploy.yml). prune nunca toca imágenes que use un contenedor (ni el de PROD ni el de DEV).
docker image prune -f --filter "label=org.opencontainers.image.title=belopia-backend" >/dev/null
log "imágenes de $AMB conservadas:"
docker images "$REPO" --format "{{.Tag}} {{.ID}} {{.CreatedSince}}" | grep -E "^($AMB-sha-|${ETIQ_COMPOSE##*:} )" | sed "s/^/  /" || true
