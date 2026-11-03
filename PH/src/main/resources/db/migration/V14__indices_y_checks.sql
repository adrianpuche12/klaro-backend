-- V14 — Índices para los listados por local, un turno abierto por local y CHECKs de
-- montos/stock (propuesta "Guardas de RLS al arrancar, índices y restricciones",
-- 2026-11-03). V13 está reservada para otro cambio en curso.
--
-- Todo es aditivo: no cambia datos ni políticas RLS. Un índice sobre una tabla con RLS
-- no toca la política; al contrario, la condición tenant_id = app_current_tenant() que
-- agrega la política puede usar los índices que empiezan por tenant_id.
--
-- Sin CONCURRENTLY: Flyway corre cada migración en una transacción y CREATE INDEX
-- CONCURRENTLY no puede ir dentro de una. Con el volumen actual (tablas chicas) el
-- bloqueo de escritura dura milisegundos. En una base grande habría que crear estos
-- índices a mano con CONCURRENTLY, fuera de Flyway, ANTES de desplegar, y dejar en la
-- migración los mismos CREATE INDEX IF NOT EXISTS (que entonces no hacen nada).

-- ── 1. Listados por empresa + local + fecha (orden de las consultas reales) ──
-- sales:               findByStoreIdAndTenantIdAndDateRange ... ORDER BY created_at DESC
-- shifts:              findByStoreIdAndTenantIdOrderByOpenedAtDesc
-- transactions:        findByStoreIdAndTenantIdOrderByDateDesc
-- inventory_movements: findByStoreIdAndTenantIdOrderByCreatedAtDesc
-- bank_deposits:       findByStoreIdAndTenantIdOrderByDepositDateDesc
CREATE INDEX IF NOT EXISTS idx_sales_tenant_store_created
    ON sales (tenant_id, store_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_shifts_tenant_store_opened
    ON shifts (tenant_id, store_id, opened_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_tenant_store_date
    ON transactions (tenant_id, store_id, date DESC);
CREATE INDEX IF NOT EXISTS idx_inventory_movements_tenant_store_created
    ON inventory_movements (tenant_id, store_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bank_deposits_tenant_store_date
    ON bank_deposits (tenant_id, store_id, deposit_date DESC);

-- Los informes por empresa filtran por fecha de venta sin local
-- (findByTenantIdAndDateRangeStrict).
CREATE INDEX IF NOT EXISTS idx_sales_tenant_sale_date
    ON sales (tenant_id, sale_date);

-- transactions.gasto_admin_id no es FK declarada pero se busca por ella
-- (findByGastoAdminId, al editar/borrar un gasto de administración).
CREATE INDEX IF NOT EXISTS idx_transactions_gasto_admin
    ON transactions (gasto_admin_id) WHERE gasto_admin_id IS NOT NULL;

-- ── 2. Un solo turno abierto por local ───────────────────────────────────────
-- ShiftService chequea y después inserta: dos aperturas simultáneas pasaban las
-- dos. Este índice lo hace imposible en la base. store.id es global (no se
-- repite entre empresas), así que no hace falta tenant_id en la clave.
-- Verificado en DEV antes de escribir esto: 0 locales con más de un turno OPEN.
CREATE UNIQUE INDEX IF NOT EXISTS ux_shift_open ON shifts (store_id) WHERE status = 'OPEN';

-- ── 3. Índice en toda FK que no lo tenga ─────────────────────────────────────
-- Se recorre el catálogo (como V11 con las políticas) en vez de listar a mano.
-- Una FK cuenta como cubierta si algún índice no parcial de la tabla empieza por sus
-- columnas en el mismo orden (los compuestos de la sección 1 empiezan por tenant_id,
-- así que no cubren la FK a store: se crea igual el índice por store_id). Sin índice, borrar/actualizar la fila padre (o el JOIN por la
-- FK) recorre toda la tabla hija. Nombre: idx_fk_<tabla>_<columnas>.
DO $$
DECLARE
    fk record;
    idx_name text;
BEGIN
    FOR fk IN
        SELECT con.conrelid,
               con.conrelid::regclass::text AS tbl,
               con.conkey,
               (SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY k.ord)
                  FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                  JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum) AS cols,
               (SELECT string_agg(a.attname, '_' ORDER BY k.ord)
                  FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                  JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum) AS col_names
        FROM pg_constraint con
        JOIN pg_namespace n ON n.oid = con.connamespace
        WHERE con.contype = 'f' AND n.nspname = 'public'
          AND NOT EXISTS (
              SELECT 1 FROM pg_index i
              WHERE i.indrelid = con.conrelid
                AND i.indpred IS NULL  -- un índice parcial no sirve para el chequeo de la FK
                AND (string_to_array(i.indkey::text, ' ')::int2[])[1:cardinality(con.conkey)] = con.conkey)
    LOOP
        idx_name := left('idx_fk_' || fk.tbl || '_' || fk.col_names, 63);
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %s (%s)', idx_name, fk.tbl, fk.cols);
    END LOOP;
END $$;

-- ── 4. CHECKs de stock y montos ──────────────────────────────────────────────
-- NOT VALID primero: la restricción rige YA para toda fila nueva o modificada, sin
-- recorrer la tabla con bloqueo fuerte. Después VALIDATE revisa las filas viejas con
-- un bloqueo que no frena lecturas ni escrituras.

-- 4.a Verificados en DEV (0 filas que violen): VALIDATE directo. Si fallara en otro
-- ambiente, la migración se cae y hay que corregir los datos antes (es lo correcto:
-- stock negativo o una venta con total negativo son errores reales).
ALTER TABLE inventory_stock ADD CONSTRAINT inventory_stock_quantity_non_negative
    CHECK (quantity >= 0) NOT VALID;
ALTER TABLE inventory_stock VALIDATE CONSTRAINT inventory_stock_quantity_non_negative;

ALTER TABLE sales ADD CONSTRAINT sales_total_non_negative
    CHECK (total >= 0) NOT VALID;
ALTER TABLE sales VALIDATE CONSTRAINT sales_total_non_negative;

-- 4.b No verificados uno por uno en DEV: se agregan igual (rigen para filas nuevas y
-- modificadas) y se validan solo si los datos viejos cumplen. Si no cumplen, queda
-- NOT VALID con un WARNING en el log de Flyway en vez de frenar el deploy; se corrigen
-- los datos y se valida a mano (ALTER TABLE ... VALIDATE CONSTRAINT ...).
ALTER TABLE sales ADD CONSTRAINT sales_amounts_non_negative
    CHECK (subtotal >= 0 AND isv >= 0
           AND COALESCE(cash_amount, 0) >= 0 AND COALESCE(card_amount, 0) >= 0) NOT VALID;
ALTER TABLE sale_items ADD CONSTRAINT sale_items_amounts_non_negative
    CHECK (subtotal >= 0 AND unit_price_snapshot >= 0) NOT VALID;
ALTER TABLE products ADD CONSTRAINT products_price_non_negative
    CHECK (price >= 0) NOT VALID;
ALTER TABLE products ADD CONSTRAINT products_min_stock_non_negative
    CHECK (min_stock >= 0) NOT VALID;
ALTER TABLE inventory_movements ADD CONSTRAINT inventory_movements_quantity_positive
    CHECK (quantity > 0) NOT VALID;
ALTER TABLE taxes ADD CONSTRAINT taxes_rate_non_negative
    CHECK (rate >= 0) NOT VALID;
ALTER TABLE tenant_config ADD CONSTRAINT tenant_config_card_surcharge_non_negative
    CHECK (card_surcharge_rate IS NULL OR card_surcharge_rate >= 0) NOT VALID;

DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT con.conrelid::regclass::text AS tbl, con.conname
        FROM pg_constraint con
        WHERE con.conname IN ('sales_amounts_non_negative', 'sale_items_amounts_non_negative',
                              'products_price_non_negative', 'products_min_stock_non_negative',
                              'inventory_movements_quantity_positive', 'taxes_rate_non_negative',
                              'tenant_config_card_surcharge_non_negative')
          AND NOT con.convalidated
    LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', r.tbl, r.conname);
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING 'V14: % queda NOT VALID en %: hay filas viejas que no cumplen (las nuevas sí se controlan)',
                r.conname, r.tbl;
        END;
    END LOOP;
END $$;
