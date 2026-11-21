#!/usr/bin/env bash
# =====================================================================
# Mejora continua v6: ENSAYO DE LA PROMOCION. Lleva una base descartable desde V1 (lo que tiene main y PROD)
# hasta la ultima migracion, con el backend en perfil prod, y verifica que todo quede andando.
#
#   tests/promocion/ensayar.sh                      # las 3 variantes
#   tests/promocion/ensayar.sh prod-vacia           # una sola
#   tests/promocion/ensayar.sh bajar                # baja todo y borra volumenes
#
# Variantes:
#   prod-vacia   base en V1 y sin filas (la forma de PROD hoy). Tiene que migrar y quedar andando.
#   con-datos    base en V1 con datos sinteticos con forma de septiembre. Tiene que migrar sin perder filas.
#   casos-borde  igual, mas un turno abierto repetido y un stock negativo. V14 TIENE que fallar, y el informe
#                dice que limpiar antes de promover.
# En las variantes que migran se cargan usuarios sinteticos y se prueba un login por papel (root, encargado,
# cajero, contador), el aislamiento entre empresas, y que un usuario de Keycloak SIN fila en app_users quede
# sin acceso (falla cerrada).
#
# Solo docker local o el runner de GitHub Actions. No toca ningun servidor: todo vive en la red interna del
# compose (sin salida a internet). Las claves se generan en cada corrida en tests/promocion/.generado/
# (ignorado por git). El resultado es tests/promocion/resultados/<variante>/informe.md: sin datos ni tokens.
# Variables opcionales: PROMOCION_SIN_BUILD=1 (usa la imagen belopia-backend:promocion ya construida).
# =====================================================================
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$RAIZ"
GEN="tests/promocion/.generado"
RES="tests/promocion/resultados"
DC=(docker compose -f docker-compose.promocion.yml --env-file "$GEN/promocion.env")
PY="${PYTHON:-python3}"
MIGRACIONES="PH/src/main/resources/db/migration"

bajar() { [ -f "$GEN/promocion.env" ] && "${DC[@]}" --profile herramientas down -v >/dev/null 2>&1 || true; }
if [ "${1:-}" = bajar ]; then bajar; exit 0; fi

VARIANTES="${*:-prod-vacia con-datos casos-borde}"
for v in $VARIANTES; do
    case "$v" in prod-vacia|con-datos|casos-borde) ;; *) echo "variante desconocida: $v"; exit 2;; esac
done

mkdir -p "$GEN" "$RES"
clave() { openssl rand -hex 16; }
if [ ! -f "$GEN/promocion.env" ]; then
    umask 077
    {
        echo "DB_MIGRATION_PASSWORD=$(clave)"
        echo "DB_APP_PASSWORD=$(clave)"
        echo "KEYCLOAK_ADMIN_PASSWORD=$(clave)"
        echo "KEYCLOAK_ADMIN_CLIENT_SECRET=$(clave)"
        echo "S3_ACCESS_KEY=promo$(openssl rand -hex 4)"
        echo "S3_SECRET_KEY=$(clave)"
        echo "LOAD_USER_PASSWORD=Promo-$(clave)"
    } > "$GEN/promocion.env"
    umask 022
fi
set -a; . "$GEN/promocion.env"; set +a

echo "== usuarios sinteticos (realm belopia-load, 2 cajeros por empresa)"
LOAD_CAJEROS=2 "$PY" tests/load/ambiente/generar_usuarios.py "$GEN" >/dev/null
chmod 644 "$GEN/realm-belopia-load.json" "$GEN/usuarios.sql" "$GEN/usuarios.json"

if [ "${PROMOCION_SIN_BUILD:-}" != 1 ]; then
    echo "== build del backend"
    docker build -q -t belopia-backend:promocion ./PH >/dev/null
fi
"${DC[@]}" --profile herramientas pull -q postgres flyway keycloak s3 curl >/dev/null

ULTIMA=$(ls "$MIGRACIONES" | sed -nE 's/^V([0-9_]+)__.*/\1/p' | tr _ . | sort -V | tail -1)
echo "== ultima migracion del repo: V$ULTIMA"

psql_() { "${DC[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U belopia_owner -d belopia_prod "$@"; }
sondear() { "${DC[@]}" exec -T postgres wget -qO- -T 5 "$@"; }
# Codigo HTTP de GET <ruta> con el token de <usuario>. La clave y el token viajan por stdin, nunca como argumento.
codigo() {
    local tok
    tok=$(printf 'grant_type=password&client_id=klaro-frontend&username=%s&password=%s' "$1" "$LOAD_USER_PASSWORD" \
        | "${DC[@]}" run --rm -T curl -s -d @- http://keycloak:8080/realms/belopia-load/protocol/openid-connect/token \
        | "$PY" -c 'import json,sys
try: print(json.load(sys.stdin).get("access_token",""))
except Exception: print("")')
    [ -n "$tok" ] || { echo "sin-token"; return 0; }
    printf 'Authorization: Bearer %s\n' "$tok" \
        | "${DC[@]}" run --rm -T curl -s -o /dev/null -w '%{http_code}' -H @- "http://backend:8080$2" || true
}
# Ultima version aplicada con exito
version_aplicada() { psql_ -At -c "SELECT coalesce((SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1), 'ninguna')"; }
CONTEOS="SELECT string_agg(t || '=' || n, ' ' ORDER BY t) FROM (VALUES
  ('tenants', (SELECT count(*) FROM tenants)), ('store', (SELECT count(*) FROM store)),
  ('products', (SELECT count(*) FROM products)), ('inventory_stock', (SELECT count(*) FROM inventory_stock)),
  ('shifts', (SELECT count(*) FROM shifts)), ('sales', (SELECT count(*) FROM sales)),
  ('sale_items', (SELECT count(*) FROM sale_items)), ('inventory_movements', (SELECT count(*) FROM inventory_movements)),
  ('app_users', (SELECT count(*) FROM app_users))) x(t, n)"

FALLAS=0
ensayar() {
    local v="$1" dir="$RES/$1" inf="$RES/$1/informe.md" ok=1 estado="" i
    mkdir -p "$dir"
    echo "== variante $v"
    bajar
    "${DC[@]}" up -d --no-build --wait postgres s3 keycloak >/dev/null

    # 1. La base queda en V1, como PROD
    "${DC[@]}" run --rm -T flyway migrate -target=1 >"$dir/flyway-v1.log" 2>&1 \
        || { echo "   flyway a V1 fallo"; tail -5 "$dir/flyway-v1.log"; return 1; }
    # (las funciones llamadas con || no cortan con set -e: cada paso que tiene que andar se chequea a mano)
    case "$v" in
        con-datos)   psql_ -v casos_borde=0 -f /promocion/v1-datos.sql || { echo "   la carga de datos V1 fallo"; return 1; } ;;
        casos-borde) psql_ -v casos_borde=1 -f /promocion/v1-datos.sql || { echo "   la carga de datos V1 fallo"; return 1; } ;;
    esac
    for i in $(seq 1 60); do sondear http://keycloak:8080/realms/belopia-load >/dev/null 2>&1 && break; sleep 3; done
    sondear http://keycloak:8080/realms/belopia-load >/dev/null 2>&1 || { echo "   el realm belopia-load no se importo"; return 1; }
    local antes; antes=$(psql_ -At -c "$CONTEOS")

    # 2. Verificacion previa (solo lectura): las mismas consultas que se corren en PROD con OK de Jorge
    psql_ -f /promocion/verificacion-previa.sql >"$dir/verificacion-previa.txt" 2>&1 || true

    # 3. El backend arranca y Flyway migra de V1 a la ultima
    "${DC[@]}" up -d --no-build backend >/dev/null
    for i in $(seq 1 75); do
        if sondear http://backend:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then estado=sano; break; fi
        if [ "$(docker inspect -f '{{.State.Status}}' "$("${DC[@]}" ps -aq backend)")" = exited ]; then estado=caido; break; fi
        sleep 4
    done
    [ -n "$estado" ] || estado=sin-respuesta
    local despues version guardia
    version=$(version_aplicada)
    despues=$(psql_ -At -c "$CONTEOS")
    "${DC[@]}" logs --no-color backend >"$dir/backend.log" 2>&1 || true
    guardia=$(grep -c 'Guardia de RLS: OK' "$dir/backend.log" || true)

    {
        echo "# Ensayo de la promocion: variante \`$v\`"
        echo
        echo "- Commit: \`${COMMIT:-local}\` · fecha: $(date -u +'%Y-%m-%d %H:%M UTC')"
        echo "- Base de partida: V1 (\`flyway -target=1\`), $(case $v in prod-vacia) echo 'sin filas (la forma de PROD hoy)';; con-datos) echo 'con datos sinteticos con forma de septiembre';; casos-borde) echo 'con datos sinteticos y casos borde a proposito (turno abierto repetido, stock negativo)';; esac)"
        echo "- Ultima migracion del repo: V$ULTIMA · aplicada al final: V$version · backend: **$estado**"
        echo "- Guardia de RLS en el log: $([ "$guardia" -gt 0 ] && echo OK || echo 'no aparece')"
        echo "- Filas antes de migrar: \`$antes\`"
        echo "- Filas despues: \`$despues\`"
        echo
        echo "## Verificacion previa (solo lectura)"
        echo '```'
        cat "$dir/verificacion-previa.txt"
        echo '```'
    } >"$inf"

    if [ "$v" = casos-borde ]; then
        # Tiene que fallar en V14, con un mensaje claro
        local motivo
        # Solo los fragmentos que explican la falla (nombre de la migracion, del indice o del CHECK)
        motivo=$(grep -oE 'Migration V[0-9_]+__[A-Za-z0-9_]+\.sql failed|could not create unique index "[a-z_]+"|check constraint "[a-z_]+"( of relation "[a-z_]+")? is violated by some row|Key \([a-z_, ]+\)=\([0-9, ]+\) is duplicated' \
                 "$dir/backend.log" | sort -u | head -5 || true)
        {
            echo
            echo "## Resultado esperado: la migracion FALLA en V14"
            if [ "$estado" != sano ] && [ -n "$motivo" ] && [ "${version%%.*}" -lt 14 ] 2>/dev/null; then
                echo "✅ Fallo donde tenia que fallar (quedo en V$version) y el backend no arranco. Motivo en el log:"
            else
                echo "❌ No fallo como se esperaba (backend $estado, V$version)."; ok=0
            fi
            echo '```'; echo "${motivo:-sin mensaje de V14 en el log}"; echo '```'
            echo
            echo "**Que limpiar en PROD antes de promover si la verificacion previa no da 0:** turnos abiertos"
            echo "repetidos en un local (cerrar el viejo) y stock negativo (ajuste de inventario a 0). La limpieza en PROD"
            echo "se aprueba aparte; las migraciones no se reescriben."
        } >>"$inf"
    else
        {
            echo
            echo "## Migracion"
            if [ "$estado" = sano ] && [ "$version" = "$ULTIMA" ] && [ "$guardia" -gt 0 ]; then
                echo "✅ V1 → V$version, backend sano con perfil prod y guardia de RLS en OK."
            else
                echo "❌ backend $estado, quedo en V$version (esperado V$ULTIMA), guardia: $guardia."; ok=0
            fi
            if [ "$v" = con-datos ]; then
                if [ "$antes" = "$despues" ]; then echo "✅ No se perdio ninguna fila."; else echo "❌ Cambiaron los conteos."; ok=0; fi
            fi
        } >>"$inf"

        if [ "$estado" = sano ]; then
            # 4. Usuarios sinteticos y un login por papel
            psql_ -v productos=2 -v dias=1 -v ventas=1 -v cajeros=2 -v stock_carrera=5 -v stock_ajuste=5 \
                  -f /semilla/semilla.sql >/dev/null || { echo "   la semilla fallo"; return 1; }
            psql_ -f /generado/usuarios.sql >/dev/null || { echo "   usuarios.sql fallo"; return 1; }
            # Usuarios que existen en Keycloak pero quedan SIN fila en app_users (como todos los de PROD hoy)
            psql_ -c "DELETE FROM user_store_access WHERE user_id IN (SELECT id FROM app_users WHERE username IN ('cajero2.a', 'root1.b'));
                      DELETE FROM app_users WHERE username IN ('cajero2.a', 'root1.b');" >/dev/null
            local fila u ruta esperado obtenido que
            {
                echo
                echo "## Acceso por papel despues de migrar"
                echo "| Que se prueba | Usuario | Ruta | Esperado | Obtenido | |"
                echo "|---|---|---|---|---|---|"
            } >>"$inf"
            while IFS='|' read -r que u ruta esperado; do
                obtenido=$(codigo "$u" "$ruta")
                if [[ "$obtenido" =~ ^($esperado)$ ]]; then fila="✅"; else fila="❌"; ok=0; fi
                echo "| $que | $u | \`$ruta\` | $esperado | $obtenido | $fila |" >>"$inf"
            done <<'CASOS'
root de la empresa A|root1.a|/api/v2/stores|200
encargado|encargado1.a|/api/v2/dashboard|200
cajero en su local|cajero1.a|/api/v2/shifts/active/11|200
contador|contador1.b|/api/v2/dashboard|200
aislamiento: encargado de A contra un local de B|encargado1.a|/api/v2/stores/21/products|403|404
sin fila en app_users: quedaria sin acceso|cajero2.a|/api/v2/stores|401|403
root sin fila en app_users (entra por el token)|root1.b|/api/v2/stores|200|401|403
CASOS
            {
                echo
                echo "Un usuario de Keycloak sin fila en \`app_users\` (salvo root) **queda sin acceso** despues de la promocion:"
                echo "antes de abrir PROD hay que crear la fila de cada usuario real (o darlo de alta de nuevo desde la app)."
            } >>"$inf"
        fi
    fi

    if [ $ok = 1 ]; then echo "   ok: $v"; else echo "   FALLO: $v"; FALLAS=$((FALLAS + 1)); fi
    sed -i "1a\\
\\
**Resultado de la variante: $([ $ok = 1 ] && echo '✅ como se esperaba' || echo '❌ distinto de lo esperado')**" "$inf"
}

for v in $VARIANTES; do ensayar "$v" || FALLAS=$((FALLAS + 1)); done
bajar
# Los logs y la salida de flyway quedan solo en el runner o en local: no se suben.
echo "== ensayo terminado: $FALLAS variante(s) distinta(s) de lo esperado"
[ "$FALLAS" = 0 ]
