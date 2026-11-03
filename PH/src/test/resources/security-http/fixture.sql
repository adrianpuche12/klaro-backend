-- Datos de los tests HTTP de seguridad (CrossTenantHttpIT, PermissionMatrixIT).
-- Se ejecuta como DUEÑO de las tablas (no sujeto a RLS) antes de cada test.
--
-- Empresa A (tenant 1): locales 1 "A Centro" y 3 "A Norte", con root, encargado, cajero y contador.
-- Empresa B (tenant 2): local 2. TODO texto de B lleva la marca SECRETOB: si aparece en una
-- respuesta pedida con un token de A, es una fuga entre empresas.
--
-- Los ids son fijos (los usa EndpointCatalog); las identidades siguen desde 1000.

DO $$
DECLARE t text;
BEGIN
    SELECT string_agg(format('%I', tablename), ', ') INTO t
    FROM pg_tables
    WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history';
    EXECUTE 'TRUNCATE ' || t || ' RESTART IDENTITY CASCADE';
END $$;

INSERT INTO tenants (id, slug, name, plan, active, created_at) VALUES
    (1, 'empresa-a', 'Empresa A', 'starter', true, now()),
    (2, 'empresa-secretob', 'Empresa SECRETOB', 'starter', true, now());

INSERT INTO tenant_config (tenant_id, currency, timezone, company_name, phone, address) VALUES
    (1, 'ARS', 'America/Argentina/Buenos_Aires', 'Empresa A SA', '111', 'Calle A 1'),
    (2, 'ARS', 'America/Argentina/Buenos_Aires', 'SECRETOB SRL', '999', 'Calle SECRETOB 2');

INSERT INTO store (id, tenant_id, name, address, phone, active, created_at) VALUES
    (1, 1, 'A Centro', 'Av A 100', '111', true, now()),
    (2, 2, 'Local SECRETOB', 'Calle SECRETOB', '999', true, now()),
    (3, 1, 'A Norte', 'Av A 300', '113', true, now()),
    (4, 1, 'A Vacio', 'Av A 400', '114', true, now());

INSERT INTO categories (id, tenant_id, store_id, name, description, active, display_order, created_at) VALUES
    (1, 1, 1, 'Bebidas A', 'cat A', true, 1, now()),
    (2, 2, 2, 'Categoria SECRETOB', 'SECRETOB', true, 1, now()),
    (3, 1, 3, 'Bebidas Norte', 'cat norte', true, 1, now()),
    (4, 1, 1, 'Vacia A', 'sin productos', true, 2, now());

INSERT INTO products (id, tenant_id, store_id, category_id, name, sku, type, price, min_stock, description, active, created_at) VALUES
    (1, 1, 1, 1, 'Cafe A', 'A-1', 'SIMPLE', 100.00, 5, 'prod A', true, now()),
    (2, 2, 2, 2, 'Producto SECRETOB', 'SECRETOB-1', 'SIMPLE', 777.00, 5, 'SECRETOB', true, now()),
    (3, 1, 3, 3, 'Cafe Norte', 'A-3', 'SIMPLE', 100.00, 5, 'prod norte', true, now()),
    (4, 1, 1, null, 'Sin uso A', 'A-4', 'SIMPLE', 10.00, 0, 'sin stock ni ventas', true, now());

INSERT INTO inventory_stock (id, tenant_id, product_id, store_id, quantity, updated_at) VALUES
    (1, 1, 1, 1, 50, now()),
    (2, 2, 2, 2, 50, now()),
    (3, 1, 3, 3, 50, now());

INSERT INTO inventory_movements (id, tenant_id, product_id, store_id, quantity, type, reason, notes, username, performed_by, source, created_at) VALUES
    (1, 1, 1, 1, 50, 'ENTRADA', 'carga A', 'A', 'encargado.a', 'encargado.a', 'MANUAL', now()),
    (2, 2, 2, 2, 50, 'ENTRADA', 'carga SECRETOB', 'SECRETOB', 'root.secretob', 'root.secretob', 'MANUAL', now());

-- Turno 1: abierto por el cajero de A. Turno 3: cerrado, abierto por el encargado (el cajero no lo ve).
INSERT INTO shifts (id, tenant_id, store_id, code, username, status, opened_at, closed_at, notes) VALUES
    (1, 1, 1, 'T-A-1', 'cajero.a', 'OPEN', now(), null, null),
    (2, 2, 2, 'T-SECRETOB-2', 'cajero.secretob', 'OPEN', now(), null, 'SECRETOB'),
    (3, 1, 1, 'T-A-3', 'encargado.a', 'CLOSED', now() - interval '1 day', now() - interval '20 hours', null);

INSERT INTO sales (id, tenant_id, shift_id, store_id, username, sale_date, status, subtotal, isv, total, payment_method, cash_amount, card_amount, created_at) VALUES
    (1, 1, 1, 1, 'cajero.a', current_date, 'OPEN', 100, 0, 100, 'CASH', 100, 0, now()),
    (2, 2, 2, 2, 'cajero.secretob', current_date, 'OPEN', 777, 0, 777, 'CASH', 777, 0, now()),
    (3, 1, 3, 1, 'encargado.a', current_date - 1, 'CONFIRMED', 100, 0, 100, 'CASH', 100, 0, now() - interval '1 day');

INSERT INTO sale_items (id, tenant_id, sale_id, product_id, quantity, unit_price_snapshot, subtotal, product_name_snapshot) VALUES
    (1, 1, 1, 1, 1, 100, 100, 'Cafe A'),
    (2, 2, 2, 2, 1, 777, 777, 'Producto SECRETOB'),
    (3, 1, 3, 1, 1, 100, 100, 'Cafe A');

INSERT INTO shift_expenses (id, tenant_id, shift_id, amount, reason, username, created_at) VALUES
    (1, 1, 1, 10, 'hielo A', 'cajero.a', now()),
    (2, 2, 2, 10, 'gasto SECRETOB', 'cajero.secretob', now());

INSERT INTO bank_deposits (id, tenant_id, store_id, deposit_date, declared_amount, notes, username, created_at) VALUES
    (1, 1, 1, current_date, 100, 'dep A', 'encargado.a', now()),
    (2, 2, 2, current_date, 777, 'dep SECRETOB', 'root.secretob', now());

INSERT INTO closing_deposits (id, tenant_id, store_id, amount, closings_count, deposit_date, period_start, period_end, username) VALUES
    (1, 1, 1, 100, 1, current_date, current_date, current_date, 'cajero.a'),
    (2, 2, 2, 777, 1, current_date, current_date, current_date, 'cajero.secretob');

INSERT INTO gasto_admin (id, tenant_id, fecha, monto, descripcion, username, created_at) VALUES
    (1, 1, current_date, 100, 'gasto A', 'encargado.a', now()),
    (2, 2, current_date, 777, 'gasto SECRETOB', 'root.secretob', now());

INSERT INTO transactions (id, tenant_id, store_id, type, amount, date, description, gasto_admin_id, image_uri) VALUES
    (1, 1, 1, 'income', 100, current_date, 'mov A', null, 'test/empresa-a/comprobantes/a.png'),
    (2, 2, 2, 'income', 777, current_date, 'mov SECRETOB', null, 'test/empresa-secretob/comprobantes/secretob.png'),
    (3, 1, 1, 'expense', 100, current_date, 'gasto A', 1, null),
    (4, 2, 2, 'expense', 333, current_date, 'gasto SECRETOB', 2, null);

INSERT INTO supplier_payments (id, tenant_id, store_id, supplier, description, amount, payment_date, username) VALUES
    (1, 1, 1, 'Prov A', 'pago A', 100, current_date, 'encargado.a'),
    (2, 2, 2, 'Prov SECRETOB', 'pago SECRETOB', 777, current_date, 'root.secretob');

INSERT INTO salary_payments (id, tenant_id, store_id, description, amount, salary_date, username) VALUES
    (1, 1, 1, 'sueldo A', 100, current_date, 'encargado.a'),
    (2, 2, 2, 'sueldo SECRETOB', 777, current_date, 'root.secretob');

INSERT INTO taxes (id, tenant_id, name, rate, type, applies_to, active, created_at) VALUES
    (1, 1, 'IVA A', 21, 'PERCENTAGE', 'ALL', true, now()),
    (2, 2, 'IVA SECRETOB', 21, 'PERCENTAGE', 'ALL', true, now());

-- Roles de la empresa A (el root NO es una fila: es el rol "root" de Keycloak).
--   Encargado: gestiona usuarios, los 9 módulos.
--   Cajero:    POS + historial (igual que el rol "Cajero" de DEV).
--   Contador:  Dashboard + finanzas + operaciones, sin gestionar usuarios.
-- Los tres acotados al local 1 (A Centro): el local 3 (A Norte) les tiene que dar 403.
INSERT INTO roles (id, tenant_id, name, level, can_manage_users, created_at) VALUES
    (1, 1, 'Encargado', 1, true, now()),
    (2, 1, 'Cajero', 3, false, now()),
    (3, 1, 'Contador', 2, false, now()),
    (4, 2, 'Rol SECRETOB', 1, true, now()),
    (5, 1, 'Sin uso', 4, false, now());

INSERT INTO role_permissions (role_id, permission, tenant_id) VALUES
    (1, 'DASHBOARD', 1), (1, 'POS', 1), (1, 'SALES_HISTORY', 1), (1, 'INVENTORY', 1),
    (1, 'TRANSACTIONS', 1), (1, 'SALARY_PAYMENTS', 1), (1, 'SUPPLIER_PAYMENTS', 1),
    (1, 'CATALOG', 1), (1, 'OPERATIONS', 1),
    (2, 'POS', 1), (2, 'SALES_HISTORY', 1),
    (3, 'DASHBOARD', 1), (3, 'TRANSACTIONS', 1), (3, 'SALARY_PAYMENTS', 1),
    (3, 'SUPPLIER_PAYMENTS', 1), (3, 'OPERATIONS', 1),
    (4, 'POS', 2);

INSERT INTO app_users (id, tenant_id, keycloak_id, username, full_name, store_id, status, role_id, created_at) VALUES
    (1, 1, 'kc-root-a', 'root.a', 'Root A', null, 'ACTIVE', null, now()),
    (2, 1, 'kc-encargado-a', 'encargado.a', 'Encargado A', 1, 'ACTIVE', 1, now()),
    (3, 1, 'kc-cajero-a', 'cajero.a', 'Cajero A', 1, 'ACTIVE', 2, now()),
    (4, 1, 'kc-contador-a', 'contador.a', 'Contador A', 1, 'ACTIVE', 3, now()),
    (5, 2, 'kc-root-b', 'root.secretob', 'Root SECRETOB', null, 'ACTIVE', null, now()),
    (6, 2, 'kc-cajero-b', 'cajero.secretob', 'Cajero SECRETOB', 2, 'ACTIVE', 4, now());

INSERT INTO user_store_access (user_id, store_id, tenant_id) VALUES
    (2, 1, 1), (3, 1, 1), (4, 1, 1), (6, 2, 2);

DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT table_name, column_name FROM information_schema.columns
             WHERE table_schema = 'public' AND is_identity = 'YES' LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN %I RESTART WITH 1000', r.table_name, r.column_name);
    END LOOP;
END $$;
