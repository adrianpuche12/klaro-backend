-- V2 — SPRINT-09: prepara app_users para perfiles de acceso acotado.
-- store_id pasa a nullable porque un perfil de solo lectura (contador, socio)
-- puede no tener un local principal fijo, solo locales accesibles (ver V3).
ALTER TABLE app_users ALTER COLUMN store_id DROP NOT NULL;
ALTER TABLE app_users ADD COLUMN business_role VARCHAR(50);
