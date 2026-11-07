#!/usr/bin/env bash
# =====================================================================
# Mejora continua v4 (k6): levanta el ambiente DESCARTABLE de Belopia y carga la semilla sintetica.
#
#   tests/load/ambiente/levantar.sh          # levanta + siembra
#   tests/load/ambiente/levantar.sh bajar    # baja todo y borra volumenes
#
# Solo docker local o el runner de GitHub Actions. No toca ningun servidor: todo vive en la red
# interna "carga" del compose (sin salida a internet). Las claves se generan en cada corrida en
# tests/load/.generado/ (ignorado por git) y mueren con el ambiente.
# Variables opcionales: LOAD_PRODUCTOS (40), LOAD_DIAS (365), LOAD_VENTAS_POR_TURNO (30), LOAD_CAJEROS (10),
# LOAD_STOCK_CARRERA (50), LOAD_RATE_LIMIT_PROD=1 (rate limit con los valores de PROD), LOAD_SIN_BUILD=1.
# =====================================================================
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$RAIZ"
GEN="tests/load/.generado"
DC=(docker compose -f docker-compose.load.yml --env-file "$GEN/load.env")

if [ "${1:-}" = bajar ]; then
    [ -f "$GEN/load.env" ] && "${DC[@]}" --profile k6 down -v --remove-orphans || true
    exit 0
fi

PRODUCTOS="${LOAD_PRODUCTOS:-40}"
DIAS="${LOAD_DIAS:-365}"
VENTAS="${LOAD_VENTAS_POR_TURNO:-30}"
export LOAD_CAJEROS="${LOAD_CAJEROS:-10}"
STOCK_CARRERA="${LOAD_STOCK_CARRERA:-50}"

mkdir -p "$GEN" tests/load/resultados
chmod 777 tests/load/resultados   # el contenedor de k6 corre con otro usuario
clave() { openssl rand -hex 16; }
if [ ! -f "$GEN/load.env" ]; then
    umask 077
    {
        echo "DB_MIGRATION_PASSWORD=$(clave)"
        echo "DB_APP_PASSWORD=$(clave)"
        echo "KEYCLOAK_ADMIN_PASSWORD=$(clave)"
        echo "KEYCLOAK_ADMIN_CLIENT_SECRET=$(clave)"
        echo "MINIO_USER=carga$(openssl rand -hex 4)"
        echo "MINIO_PASSWORD=$(clave)"
        echo "LOAD_USER_PASSWORD=Carga-$(clave)"
        if [ "${LOAD_RATE_LIMIT_PROD:-}" = 1 ]; then
            # valores de PROD (los de la tabla): solo para rate-limit.js
            echo "LOAD_RATE_LIMIT_USER_PER_MIN=300"
            echo "LOAD_RATE_LIMIT_ANON_IP_PER_MIN=100"
            echo "LOAD_RATE_LIMIT_IP_CEILING_PER_MIN=1000"
        fi
    } > "$GEN/load.env"
    umask 022
fi
set -a; . "$GEN/load.env"; set +a

echo "== usuarios sinteticos"
"${PYTHON:-python3}" tests/load/ambiente/generar_usuarios.py "$GEN"
chmod 644 "$GEN/realm-belopia-load.json" "$GEN/usuarios.sql" "$GEN/usuarios.json"   # Keycloak y psql leen con otro usuario

echo "== build del backend (perfil load)"
if [ "${LOAD_SIN_BUILD:-}" != 1 ]; then
    docker build -q -t belopia-backend:load ./PH >/dev/null
fi

echo "== levantando postgres, keycloak, minio y backend"
"${DC[@]}" up -d --no-build postgres keycloak minio backend

esperar() { # $1 descripcion, $2.. comando
    local desc="$1"; shift
    for _ in $(seq 1 90); do
        if "$@" >/dev/null 2>&1; then echo "   ok: $desc"; return 0; fi
        sleep 4
    done
    echo "   TIMEOUT: $desc"; return 1
}
# Postgres (alpine) tiene wget de busybox y esta en la misma red interna: las sondas salen de ahi.
dentro() { "${DC[@]}" exec -T postgres wget -qO- "$@"; }

esperar "backend sano (Flyway aplicado)" dentro http://backend:8080/actuator/health || { "${DC[@]}" logs --tail 200 backend; exit 1; }
esperar "realm belopia-load importado" dentro http://keycloak:8080/realms/belopia-load || { "${DC[@]}" logs --tail 100 keycloak; exit 1; }

echo "== semilla sintetica"
PSQL=("${DC[@]}" exec -T postgres psql -q -U belopia_owner -d belopia_load)
"${PSQL[@]}" -v productos="$PRODUCTOS" -v dias="$DIAS" -v ventas="$VENTAS" -v cajeros="$LOAD_CAJEROS" \
    -v stock_carrera="$STOCK_CARRERA" -f /semilla/semilla.sql
"${PSQL[@]}" -f /generado/usuarios.sql
"${PSQL[@]}" -At -f /semilla/datos.sql | grep '^{' > "$GEN/datos.json"
chmod 644 "$GEN/datos.json"
echo "   datos.json: $(wc -c < "$GEN/datos.json") bytes"

echo "== login de prueba (token del Keycloak propio; se miran solo los nombres de los claims)"
# Un usuario por papel: el token tiene que traer sub, tenant_id, rol de realm, preferred_username y azp
for u in root1.a encargado1.a cajero1.a contador1.b; do
    dentro --post-data "grant_type=password&client_id=klaro-frontend&username=$u&password=$LOAD_USER_PASSWORD" \
        http://keycloak:8080/realms/belopia-load/protocol/openid-connect/token \
      | "${PYTHON:-python3}" -c '
import base64, json, sys
tok = json.load(sys.stdin)["access_token"].split(".")[1]
c = json.loads(base64.urlsafe_b64decode(tok + "=" * (-len(tok) % 4)))
faltan = [k for k in ("sub", "tenant_id", "preferred_username", "azp") if not c.get(k)]
roles = c.get("realm_access", {}).get("roles", [])
assert not faltan, "faltan claims: %s" % faltan
assert c["azp"] == "klaro-frontend", "azp inesperado"
assert "root" in roles or "staff" in roles, "sin rol root/staff"
print("   ok: %s tenant=%s roles=%s" % (c["preferred_username"], c["tenant_id"], ",".join(r for r in roles if r in ("root", "staff"))))
'
done

echo "== ambiente listo"
