#!/usr/bin/env bash
# Mejora continua v4 (k6): rol de aplicacion con LOGIN en el Postgres descartable, ANTES de que corra V11
# (igual que HttpSecurityTestBase: "paso operativo previo al deploy"). V11 lo encuentra creado y le da los
# permisos. La app conecta con este rol (sin BYPASSRLS): RLS aplica de verdad. La clave viene del compose
# (generada en cada corrida).
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v app_pw="$DB_APP_PASSWORD" <<'SQL'
CREATE ROLE belopia_load_app LOGIN PASSWORD :'app_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
SQL
