-- V4.1 — corrige un error de V4: tenant_id en el resto del esquema (app_users,
-- store, etc.) es una columna simple, nunca una FK real a una tabla `tenants`
-- poblada -- esa tabla está vacía hoy (el onboarding de tenants todavía no
-- existe como flujo). La FK real que V4 le agregó a roles.tenant_id rompe
-- la migración V5 en cualquier ambiente sin filas en `tenants`.
ALTER TABLE roles DROP CONSTRAINT IF EXISTS roles_tenant_id_fkey;
