#!/usr/bin/env bash
# Mejora continua v6 (ensayo de la promocion): rol de aplicacion <base>_app con LOGIN, creado ANTES de que
# corra V11, como el paso operativo previo al deploy (en PROD es belopia_prod_app). Sin BYPASSRLS.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v app_pw="$DB_APP_PASSWORD" \
     -v app_role="${POSTGRES_DB}_app" <<'SQL'
CREATE ROLE :"app_role" LOGIN PASSWORD :'app_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
SQL
