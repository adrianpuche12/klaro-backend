-- V6 — SPRINT-14: soft-delete simple para app_users. delete() dejó de borrar
-- la fila (necesaria para trazabilidad -- created_by, ventas históricas, etc.).
ALTER TABLE app_users DROP CONSTRAINT app_users_status_check;
ALTER TABLE app_users ADD CONSTRAINT app_users_status_check
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'));
