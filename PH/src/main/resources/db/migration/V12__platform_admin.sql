-- V12 — SPRINT-16: administrador de plataforma (tenant 0).
--
-- El administrador de plataforma (usuario de Nilo con tenant_id = 0 y rol de
-- Keycloak "platform") da de alta y administra empresas. Con RLS (V11) el
-- tenant 0 no veía ninguna fila: esta migración le habilita SOLO las tablas
-- que describen a la empresa (tenants y tenant_config). Las tablas de negocio
-- (ventas, stock, usuarios, finanzas...) NO cambian: el tenant 0 sigue sin ver
-- ningún dato de negocio de ninguna empresa (decisión de Jorge, 01-Nov-2026).

-- ── 1. Políticas: tenant 0 = plataforma, solo en tenants y tenant_config ─────
DROP POLICY tenant_isolation ON tenants;
CREATE POLICY tenant_isolation ON tenants
    USING (id = app_current_tenant() OR app_current_tenant() = 0)
    WITH CHECK (id = app_current_tenant() OR app_current_tenant() = 0);

DROP POLICY tenant_isolation ON tenant_config;
CREATE POLICY tenant_isolation ON tenant_config
    USING (tenant_id = app_current_tenant() OR app_current_tenant() = 0)
    WITH CHECK (tenant_id = app_current_tenant() OR app_current_tenant() = 0);

-- ── 2. Fila de tenants para empresas que operan sin ella ─────────────────────
-- La empresa 1 se creó antes de que existiera el alta de empresas y no tiene
-- fila en tenants. Se crea con slug "tenant_<id>", el mismo prefijo que ya usa
-- R2StorageService como fallback, para que los archivos existentes sigan
-- resolviendo al mismo tenant. Solo se crea si la empresa tiene datos.
INSERT INTO tenants (id, slug, name, plan, active, created_at)
SELECT DISTINCT s.tenant_id,
       'tenant_' || s.tenant_id,
       COALESCE(NULLIF(tc.company_name, ''), 'Empresa ' || s.tenant_id),
       'starter',
       true,
       now()
FROM store s
LEFT JOIN tenant_config tc ON tc.tenant_id = s.tenant_id
WHERE s.tenant_id > 0
  AND NOT EXISTS (SELECT 1 FROM tenants t WHERE t.id = s.tenant_id);

-- La secuencia del id sigue después del mayor id usado (las filas de arriba
-- se insertaron con id explícito).
SELECT setval('tenants_id_seq', GREATEST((SELECT COALESCE(MAX(id), 0) FROM tenants), 1), (SELECT COUNT(*) > 0 FROM tenants));
