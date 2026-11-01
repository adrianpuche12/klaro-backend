-- V11 — Aislamiento multi-tenant a nivel base de datos (Postgres Row Level Security).
--
-- Hasta V10 el aislamiento entre tenants dependía solo de que cada query del
-- backend filtrara por tenant_id. Esta migración agrega una segunda barrera,
-- independiente del código: Postgres filtra cada fila según el tenant de la
-- conexión (variable de sesión app.tenant_id, seteada por TenantAwareDataSource
-- en cada checkout del pool a partir del JWT).
--
-- Modelo de roles:
--   * Dueño de las tablas (quien corre Flyway, hoy belopia_owner): NO está sujeto
--     a RLS (no se usa FORCE). Solo lo usan las migraciones y operaciones.
--   * Rol de aplicación <base>_app (belopia_dev_app / belopia_prod_app): se
--     deriva del nombre de la base, así dev y prod nunca comparten rol aunque
--     vivan en el mismo servidor. Sin ownership ni BYPASSRLS, sujeto a RLS.
--     El backend debe conectarse con este rol para que RLS tenga efecto.
--
-- Sin tenant en la sesión, las políticas no devuelven ninguna fila
-- (fail-closed): nunca "todas".
--
-- Despliegue seguro: mientras el backend siga conectándose como dueño, esta
-- migración no cambia ningún comportamiento. RLS se activa recién al cambiar
-- DB_USER al rol de aplicación.

-- ── 1. Tenant actual de la sesión ─────────────────────────────────────────────
CREATE OR REPLACE FUNCTION app_current_tenant() RETURNS bigint
    LANGUAGE sql STABLE
AS $$
    SELECT NULLIF(current_setting('app.tenant_id', true), '')::bigint
$$;

-- ── 2. tenant_id en las tablas hijas que no lo tenían ─────────────────────────
-- Se completa desde la tabla padre. El DEFAULT toma el tenant de la sesión, así
-- las inserciones de JPA (que no mapean esta columna en join tables) lo reciben
-- solas; sin tenant en la sesión el NOT NULL rechaza la inserción.

ALTER TABLE sale_items ADD COLUMN tenant_id BIGINT;
UPDATE sale_items si SET tenant_id = s.tenant_id FROM sales s WHERE s.id = si.sale_id;

ALTER TABLE user_permissions ADD COLUMN tenant_id BIGINT;
UPDATE user_permissions up SET tenant_id = u.tenant_id FROM app_users u WHERE u.id = up.user_id;

ALTER TABLE user_store_access ADD COLUMN tenant_id BIGINT;
UPDATE user_store_access usa SET tenant_id = u.tenant_id FROM app_users u WHERE u.id = usa.user_id;

ALTER TABLE role_permissions ADD COLUMN tenant_id BIGINT;
UPDATE role_permissions rp SET tenant_id = r.tenant_id FROM roles r WHERE r.id = rp.role_id;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['sale_items', 'user_permissions', 'user_store_access', 'role_permissions'] LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN tenant_id SET DEFAULT app_current_tenant()', t);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN tenant_id SET NOT NULL', t);
        EXECUTE format('CREATE INDEX idx_%s_tenant ON %I (tenant_id)', t, t);
    END LOOP;
END $$;

-- ── 3. Políticas RLS en toda tabla con tenant_id ──────────────────────────────
-- Se recorre el catálogo en vez de listar tablas a mano: ninguna tabla de
-- negocio queda afuera. Las tablas que se creen en migraciones futuras deben
-- agregar su propia política (lo verifica RowLevelSecurityTest).
DO $$
DECLARE t text;
BEGIN
    FOR t IN
        SELECT c.table_name
        FROM information_schema.columns c
        JOIN information_schema.tables tb
          ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
        WHERE c.table_schema = 'public'
          AND c.column_name = 'tenant_id'
          AND tb.table_type = 'BASE TABLE'
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I '
            'USING (tenant_id = app_current_tenant()) '
            'WITH CHECK (tenant_id = app_current_tenant())', t);
    END LOOP;
END $$;

-- La tabla tenants se identifica por id: cada tenant solo ve su propia fila.
-- El alta de tenants nuevos queda fuera del rol de aplicación (la hará el
-- administrador de plataforma, con su propia conexión).
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = app_current_tenant())
    WITH CHECK (id = app_current_tenant());

-- ── 4. Rol de aplicación y permisos ───────────────────────────────────────────
-- Se crea sin LOGIN: la contraseña y el LOGIN se habilitan fuera del repo
-- (ALTER ROLE ... LOGIN PASSWORD ...), nunca versionados.
DO $$
DECLARE
    app_role text := current_database() || '_app';
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        EXECUTE format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS', app_role);
    END IF;

    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), app_role);
    -- Solo los roles con permiso explícito deben poder conectarse a esta base.
    -- Revocar requiere ser dueño de la base; si no, queda como paso operativo.
    IF EXISTS (SELECT 1 FROM pg_database d
               WHERE d.datname = current_database()
                 AND pg_get_userbyid(d.datdba) = current_user) THEN
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), current_user);
        EXECUTE format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', current_database());
    ELSE
        RAISE NOTICE 'V11: % no es dueño de la base %, CONNECT de PUBLIC no se revocó',
            current_user, current_database();
    END IF;

    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', app_role);
    EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO %I', app_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION app_current_tenant() TO %I', app_role);
    EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', app_role);

    -- Tablas y secuencias que creen migraciones futuras (como el mismo dueño).
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', app_role);
END $$;
