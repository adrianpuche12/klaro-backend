-- Mejora continua v4 (k6): datos SINTETICOS del ambiente descartable de Belopia (nunca datos reales).
-- Corre como DUENO de las tablas (sin RLS), despues de Flyway y ANTES de usuarios.sql.
-- Adaptado de PH/src/test/resources/security-http/fixture.sql: mismos roles y permisos que DEV.
--
-- 3 empresas (carga-a/b/c, tenants 1..3), 2 locales "normales" por empresa (ids 11,12 / 21,22 / 31,32)
-- con :productos productos, stock alto, un turno ABIERTO y :dias dias de historial (un turno cerrado por
-- dia y :ventas ventas por turno, con sus items y movimientos de inventario).
-- La empresa A tiene ademas dos locales para las carreras:
--   91 "Carga carrera POS":    1 producto (91001) con stock :stock_carrera y un turno abierto (pos-concurrencia.js)
--   92 "Carga idempotencia":   1 producto (92001) con stock alto y un turno abierto (idempotencia.js)
--   93 "Carga turnos":         SIN turno abierto: lo abren dos cajas a la vez una y otra vez (turnos.js)
--   94 "Carga ajuste y venta": 1 producto (94001) con stock :stock_ajuste y un turno abierto (ajuste-vs-venta.js)
-- Variables psql (con valores por defecto en levantar.sh): productos, dias, ventas, cajeros, stock_carrera, stock_ajuste.
\set ON_ERROR_STOP on
BEGIN;

INSERT INTO tenants (id, slug, name, plan, active, created_at) VALUES
    (1, 'carga-a', 'Empresa Carga A', 'starter', true, now()),
    (2, 'carga-b', 'Empresa Carga B', 'starter', true, now()),
    (3, 'carga-c', 'Empresa Carga C', 'starter', true, now());

INSERT INTO tenant_config (tenant_id, currency, timezone, company_name, phone, address)
SELECT id, 'ARS', 'America/Argentina/Buenos_Aires', name || ' SA (prueba)', '000', 'Calle Sintetica ' || id
FROM tenants WHERE slug LIKE 'carga-%';

-- Locales
INSERT INTO store (id, tenant_id, name, address, phone, active, created_at)
SELECT t.id * 10 + n, t.id, 'Carga ' || upper(chr(96 + t.id::int)) || CASE n WHEN 1 THEN ' Centro' ELSE ' Norte' END,
       'Av Sintetica ' || (t.id * 10 + n), '000', true, now()
FROM tenants t CROSS JOIN generate_series(1, 2) n WHERE t.slug LIKE 'carga-%';
INSERT INTO store (id, tenant_id, name, address, phone, active, created_at) VALUES
    (91, 1, 'Carga carrera POS', 'Av Sintetica 91', '000', true, now()),
    (92, 1, 'Carga idempotencia', 'Av Sintetica 92', '000', true, now()),
    (93, 1, 'Carga turnos', 'Av Sintetica 93', '000', true, now()),
    (94, 1, 'Carga ajuste y venta', 'Av Sintetica 94', '000', true, now());

-- Una categoria por local
INSERT INTO categories (id, tenant_id, store_id, name, description, active, display_order, created_at)
SELECT s.id, s.tenant_id, s.id, 'Categoria ' || s.id, 'sintetica', true, 1, now() FROM store s WHERE s.tenant_id IN (1, 2, 3);

-- Productos: id = local * 1000 + n, precio sintetico 500 + 25 * n
INSERT INTO products (id, tenant_id, store_id, category_id, name, sku, type, price, min_stock, description, active, created_at)
SELECT s.id * 1000 + n, s.tenant_id, s.id, s.id, 'Producto ' || s.id || '-' || n, 'C-' || s.id || '-' || n, 'SIMPLE',
       500 + 25 * n, 5, 'sintetico', true, now()
FROM store s CROSS JOIN generate_series(1, :productos) n WHERE s.tenant_id IN (1, 2, 3) AND s.id < 90;
INSERT INTO products (id, tenant_id, store_id, category_id, name, sku, type, price, min_stock, description, active, created_at) VALUES
    (91001, 1, 91, 91, 'Producto carrera', 'C-91-1', 'SIMPLE', 1000, 0, 'stock limitado para pos-concurrencia.js', true, now()),
    (92001, 1, 92, 92, 'Producto idempotencia', 'C-92-1', 'SIMPLE', 1000, 0, 'stock alto para idempotencia.js', true, now()),
    (94001, 1, 94, 94, 'Producto ajuste y venta', 'C-94-1', 'SIMPLE', 1000, 0, 'stock chico para ajuste-vs-venta.js', true, now());

-- Stock y su movimiento de entrada
INSERT INTO inventory_stock (tenant_id, product_id, store_id, quantity, updated_at)
SELECT p.tenant_id, p.id, p.store_id,
       CASE p.id WHEN 91001 THEN :stock_carrera WHEN 94001 THEN :stock_ajuste ELSE 1000000 END, now()
FROM products p WHERE p.tenant_id IN (1, 2, 3);
INSERT INTO inventory_movements (tenant_id, product_id, store_id, quantity, type, reason, notes, username, performed_by, source, created_at)
SELECT st.tenant_id, st.product_id, st.store_id, st.quantity, 'ENTRADA', 'carga inicial', 'sintetico',
       'encargado1.' || chr(96 + st.tenant_id::int), 'encargado1.' || chr(96 + st.tenant_id::int), 'MANUAL', now() - interval '400 days'
FROM inventory_stock st WHERE st.tenant_id IN (1, 2, 3);

-- Impuesto de prueba por empresa (el mismo del fixture de los tests HTTP)
INSERT INTO taxes (tenant_id, name, rate, type, applies_to, active, created_at)
SELECT id, 'IVA prueba', 21, 'PERCENTAGE', 'ALL', true, now() FROM tenants WHERE slug LIKE 'carga-%';

-- Roles de cada empresa: los mismos del fixture (y de DEV). El root no es una fila: es el rol "root" de Keycloak.
INSERT INTO roles (tenant_id, name, level, can_manage_users, created_at)
SELECT t.id, r.name, r.level, r.manage, now()
FROM tenants t CROSS JOIN (VALUES ('Encargado', 1, true), ('Cajero', 3, false), ('Contador', 2, false)) r(name, level, manage)
WHERE t.slug LIKE 'carga-%';
INSERT INTO role_permissions (role_id, permission, tenant_id)
SELECT r.id, p.permission, r.tenant_id
FROM roles r JOIN (VALUES
    ('Encargado', 'DASHBOARD'), ('Encargado', 'POS'), ('Encargado', 'SALES_HISTORY'), ('Encargado', 'INVENTORY'),
    ('Encargado', 'TRANSACTIONS'), ('Encargado', 'SALARY_PAYMENTS'), ('Encargado', 'SUPPLIER_PAYMENTS'),
    ('Encargado', 'CATALOG'), ('Encargado', 'OPERATIONS'),
    ('Cajero', 'POS'), ('Cajero', 'SALES_HISTORY'),
    ('Contador', 'DASHBOARD'), ('Contador', 'TRANSACTIONS'), ('Contador', 'SALARY_PAYMENTS'),
    ('Contador', 'SUPPLIER_PAYMENTS'), ('Contador', 'OPERATIONS')
) p(role, permission) ON p.role = r.name
WHERE r.tenant_id IN (1, 2, 3);

-- Las identidades siguen despues de los ids fijos
SELECT setval(pg_get_serial_sequence('tenants', 'id'), (SELECT max(id) FROM tenants));
SELECT setval(pg_get_serial_sequence('store', 'id'), (SELECT max(id) FROM store));
SELECT setval(pg_get_serial_sequence('categories', 'id'), (SELECT max(id) FROM categories));
SELECT setval(pg_get_serial_sequence('products', 'id'), (SELECT max(id) FROM products));

-- Turno ABIERTO en cada local (uno solo por local: ux_shift_open), menos el 93: ese lo abre turnos.js
INSERT INTO shifts (tenant_id, store_id, code, username, status, opened_at)
SELECT s.tenant_id, s.id, 'T-' || s.id, 'encargado1.' || chr(96 + s.tenant_id::int), 'OPEN', now() - interval '1 hour'
FROM store s WHERE s.tenant_id IN (1, 2, 3) AND s.id <> 93;

-- Historial: un turno cerrado por dia y por local normal, de un cajero en ronda
INSERT INTO shifts (tenant_id, store_id, code, username, status, opened_at, closed_at)
SELECT s.tenant_id, s.id, 'H-' || s.id || '-' || d, 'cajero' || (1 + d % :cajeros) || '.' || chr(96 + s.tenant_id::int), 'CLOSED',
       (current_date - d) + time '09:00', (current_date - d) + time '21:00'
FROM store s CROSS JOIN generate_series(1, :dias) d WHERE s.tenant_id IN (1, 2, 3) AND s.id < 90;

-- :ventas ventas por turno cerrado, cada una con 2 items (cantidades 1 y 2)
CREATE TEMP TABLE plan_ventas ON COMMIT DROP AS
SELECT nextval(pg_get_serial_sequence('sales', 'id')) AS sale_id, sh.id AS shift_id, sh.tenant_id, sh.store_id,
       sh.username, sh.opened_at, k,
       1 + (k % :productos) AS n1, 1 + ((k * 7 + 3) % :productos) AS n2
FROM shifts sh CROSS JOIN generate_series(1, :ventas) k WHERE sh.code LIKE 'H-%';

INSERT INTO sales (id, tenant_id, shift_id, store_id, username, sale_date, status, subtotal, isv, total, payment_method,
                   cash_amount, card_amount, created_at)
SELECT v.sale_id, v.tenant_id, v.shift_id, v.store_id, v.username, v.opened_at::date, 'CONFIRMED',
       x.sub, round(x.sub * 0.21, 2), x.sub + round(x.sub * 0.21, 2),
       CASE WHEN v.k % 3 = 0 THEN 'CARD' ELSE 'CASH' END,
       CASE WHEN v.k % 3 = 0 THEN 0 ELSE x.sub + round(x.sub * 0.21, 2) END,
       CASE WHEN v.k % 3 = 0 THEN x.sub + round(x.sub * 0.21, 2) ELSE 0 END,
       v.opened_at + v.k * interval '1 minute'
FROM plan_ventas v CROSS JOIN LATERAL (SELECT (500 + 25 * v.n1) + 2 * (500 + 25 * v.n2) AS sub) x;

INSERT INTO sale_items (tenant_id, sale_id, product_id, quantity, unit_price_snapshot, subtotal, product_name_snapshot)
SELECT v.tenant_id, v.sale_id, v.store_id * 1000 + i.n, i.q, 500 + 25 * i.n, i.q * (500 + 25 * i.n),
       'Producto ' || v.store_id || '-' || i.n
FROM plan_ventas v CROSS JOIN LATERAL (VALUES (v.n1, 1), (v.n2, 2)) i(n, q);

INSERT INTO inventory_movements (tenant_id, product_id, store_id, quantity, type, reason, notes, username, performed_by, source, created_at)
SELECT v.tenant_id, v.store_id * 1000 + i.n, v.store_id, i.q, 'SALIDA', 'Venta', 'sintetico', v.username, v.username, 'SALE',
       v.opened_at + v.k * interval '1 minute'
FROM plan_ventas v CROSS JOIN LATERAL (VALUES (v.n1, 1), (v.n2, 2)) i(n, q);

COMMIT;

ANALYZE;

-- Resumen para el log del workflow (solo conteos)
SELECT t.slug,
       (SELECT count(*) FROM store s WHERE s.tenant_id = t.id) AS locales,
       (SELECT count(*) FROM products p WHERE p.tenant_id = t.id) AS productos,
       (SELECT count(*) FROM shifts sh WHERE sh.tenant_id = t.id) AS turnos,
       (SELECT count(*) FROM sales v WHERE v.tenant_id = t.id) AS ventas,
       (SELECT count(*) FROM inventory_movements m WHERE m.tenant_id = t.id) AS movimientos
FROM tenants t WHERE t.slug LIKE 'carga-%' ORDER BY t.slug;
