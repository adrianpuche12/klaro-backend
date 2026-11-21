-- Mejora continua v6 (ensayo de la promocion): datos SINTETICOS con la forma del esquema V1 (el de main),
-- para las variantes "con-datos" y "casos-borde". Nunca datos reales. Corre como dueno, con la base en V1.
-- 2 empresas (sept-a, sept-b; ids 101 y 102) con 2 locales cada una, productos, stock, turnos cerrados y uno
-- abierto por local, ventas con items y movimientos, y un usuario de app por empresa (como en septiembre).
-- Los ids son altos para no chocar con la semilla de k6 (tenants 1..3) que se carga despues de migrar.
-- Variable psql casos_borde (0/1): agrega a proposito lo que hace fallar V14 (un segundo turno abierto en un
-- local y un stock negativo).
\set ON_ERROR_STOP on
BEGIN;

INSERT INTO tenants (id, slug, name, plan, active, created_at) VALUES
    (101, 'sept-a', 'Empresa Septiembre A', 'starter', true, '2026-09-01'),
    (102, 'sept-b', 'Empresa Septiembre B', 'starter', true, '2026-09-01');

INSERT INTO tenant_config (tenant_id, currency, timezone, company_name, phone, address)
SELECT id, 'ARS', 'America/Argentina/Buenos_Aires', name || ' (prueba)', '000', 'Calle Sintetica ' || id
  FROM tenants WHERE id IN (101, 102);

INSERT INTO store (id, tenant_id, name, address, phone, active, created_at)
SELECT 50000 + t.id * 10 + n, t.id, 'Local ' || t.slug || '-' || n, 'Av Sintetica ' || n, '000', true, '2026-09-01'
  FROM tenants t CROSS JOIN generate_series(1, 2) n WHERE t.id IN (101, 102);

INSERT INTO categories (id, tenant_id, store_id, name, description, active, display_order, created_at)
SELECT s.id, s.tenant_id, s.id, 'Categoria ' || s.id, 'sintetica', true, 1, '2026-09-01'
  FROM store s WHERE s.tenant_id IN (101, 102);

INSERT INTO products (id, tenant_id, store_id, category_id, name, sku, type, price, min_stock, description, active, created_at)
SELECT s.id * 100 + n, s.tenant_id, s.id, s.id, 'Producto ' || s.id || '-' || n, 'S-' || s.id || '-' || n, 'SIMPLE',
       100 + n, 2, 'sintetico', true, '2026-09-01'
  FROM store s CROSS JOIN generate_series(1, 5) n WHERE s.tenant_id IN (101, 102);

INSERT INTO inventory_stock (id, quantity, product_id, store_id, tenant_id, updated_at)
SELECT p.id, 20, p.id, p.store_id, p.tenant_id, '2026-09-05' FROM products p WHERE p.tenant_id IN (101, 102);

-- 3 turnos cerrados y 1 abierto por local
INSERT INTO shifts (id, opened_at, closed_at, store_id, tenant_id, status, code, username)
SELECT s.id * 10 + d, timestamp '2026-09-01 09:00' + d * interval '1 day',
       timestamp '2026-09-01 18:00' + d * interval '1 day', s.id, s.tenant_id, 'CLOSED',
       'T-' || s.id || '-' || d, 'cajero.' || s.tenant_id
  FROM store s CROSS JOIN generate_series(1, 3) d WHERE s.tenant_id IN (101, 102);
INSERT INTO shifts (id, opened_at, store_id, tenant_id, status, code, username)
SELECT s.id * 10 + 9, timestamp '2026-09-05 09:00', s.id, s.tenant_id, 'OPEN', 'T-' || s.id || '-9', 'cajero.' || s.tenant_id
  FROM store s WHERE s.tenant_id IN (101, 102);

-- 2 ventas por turno cerrado, con su item y su movimiento de inventario
INSERT INTO sales (id, card_amount, cash_amount, isv, sale_date, subtotal, total, created_at, shift_id, store_id,
                   tenant_id, payment_method, status, username)
SELECT sh.id * 10 + k, 0, 115, 15, sh.opened_at::date, 100, 115, sh.opened_at + k * interval '1 hour', sh.id,
       sh.store_id, sh.tenant_id, 'CASH', 'CONFIRMED', sh.username
  FROM shifts sh CROSS JOIN generate_series(1, 2) k WHERE sh.status = 'CLOSED' AND sh.tenant_id IN (101, 102);
INSERT INTO sale_items (id, quantity, subtotal, unit_price_snapshot, product_id, sale_id, product_name_snapshot)
SELECT v.id, 1, 100, 100, v.store_id * 100 + 1, v.id, 'Producto ' || v.store_id || '-1'
  FROM sales v WHERE v.tenant_id IN (101, 102);
INSERT INTO inventory_movements (id, quantity, created_at, product_id, store_id, tenant_id, source, type, username)
SELECT v.id, 1, v.created_at, v.store_id * 100 + 1, v.store_id, v.tenant_id, 'SALE', 'OUT', v.username
  FROM sales v WHERE v.tenant_id IN (101, 102);

-- Un usuario de la app por empresa, con la forma de V1 (sin rol ni permisos: los agregan V2 a V6)
INSERT INTO app_users (id, created_at, store_id, tenant_id, status, full_name, keycloak_id, username)
SELECT 900000 + t.id, '2026-09-01', 50000 + t.id * 10 + 1, t.id, 'ACTIVE', 'Usuario ' || t.slug,
       md5('sept-' || t.id), 'usuario.' || t.slug
  FROM tenants t WHERE t.id IN (101, 102);

\if :casos_borde
-- Caso borde 1: un segundo turno abierto en el mismo local (V14 crea ux_shift_open y tiene que fallar)
INSERT INTO shifts (id, opened_at, store_id, tenant_id, status, code, username)
VALUES (510118, timestamp '2026-09-05 10:00', 51011, 101, 'OPEN', 'T-51011-8', 'cajero.101');
-- Caso borde 2: stock negativo (V14 valida inventory_stock_quantity_non_negative)
UPDATE inventory_stock SET quantity = -3 WHERE id = 5101101;
\endif

-- Las columnas id son IDENTITY: se ajustan para que los INSERT siguientes (semilla de k6, la app) no choquen
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT table_name FROM information_schema.columns
              WHERE table_schema = 'public' AND column_name = 'id' AND is_identity = 'YES'
    LOOP
        EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, ''id''), greatest((SELECT coalesce(max(id), 0) FROM %I), 1))',
                       r.table_name, r.table_name);
    END LOOP;
END $$;

COMMIT;
