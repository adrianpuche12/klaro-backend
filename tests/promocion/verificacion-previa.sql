-- Mejora continua v6: VERIFICACION PREVIA a la promocion. Solo lectura (la transaccion es READ ONLY y
-- termina en ROLLBACK): no cambia nada. Devuelve versiones y CONTEOS, nunca datos de clientes.
-- La usa el ensayo (tests/promocion/ensayar.sh) sobre la base sintetica. Son las mismas consultas que se corren
-- en PROD antes de mergear a main, a mano y SOLO con OK explicito de Jorge (checklist de promocion).
-- Cada bloque dice que tiene que dar para que la migracion no falle.
\set ON_ERROR_STOP on
\pset footer off
BEGIN TRANSACTION READ ONLY;

\echo '### Flyway: migraciones aplicadas'
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;

\echo '### Filas por tabla'
SELECT format('SELECT %L AS tabla, count(*) AS filas FROM %I.%I', table_name, table_schema, table_name)
  FROM information_schema.tables
 WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
 ORDER BY table_name \gexec

\echo '### V14: locales con mas de un turno abierto (tiene que dar 0; si no, falla ux_shift_open)'
SELECT count(*) AS locales_con_turnos_abiertos_repetidos
  FROM (SELECT store_id FROM shifts WHERE status = 'OPEN' GROUP BY store_id HAVING count(*) > 1) x;

\echo '### V14: stock negativo (tiene que dar 0; si no, falla inventory_stock_quantity_non_negative)'
SELECT count(*) AS filas_con_stock_negativo FROM inventory_stock WHERE quantity < 0;

\echo '### V14: ventas con total negativo (tiene que dar 0; si no, falla sales_total_non_negative)'
SELECT count(*) AS ventas_con_total_negativo FROM sales WHERE total < 0;

\echo '### V14 (solo aviso, quedan NOT VALID): montos negativos'
SELECT (SELECT count(*) FROM sales WHERE subtotal < 0 OR isv < 0 OR coalesce(cash_amount, 0) < 0
                                    OR coalesce(card_amount, 0) < 0) AS ventas,
       (SELECT count(*) FROM sale_items WHERE subtotal < 0 OR unit_price_snapshot < 0) AS items,
       (SELECT count(*) FROM products WHERE price < 0 OR min_stock < 0) AS productos,
       (SELECT count(*) FROM inventory_movements WHERE quantity <= 0) AS movimientos,
       (SELECT count(*) FROM taxes WHERE rate < 0) AS impuestos;

\echo '### V16: codigos de turno repetidos dentro de una empresa (tiene que dar 0)'
SELECT count(*) AS codigos_repetidos
  FROM (SELECT tenant_id, code FROM shifts GROUP BY tenant_id, code HAVING count(*) > 1) x;

\echo '### V11: rol de aplicacion <base>_app (tiene que existir, con LOGIN, sin superusuario ni BYPASSRLS)'
SELECT current_database() || '_app' AS rol_esperado,
       coalesce((SELECT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls
                   FROM pg_roles WHERE rolname = current_database() || '_app'), false) AS listo;

\echo '### Permisos: usuarios con fila en app_users (quien entra por Keycloak sin fila queda sin acceso, salvo root)'
SELECT status, count(*) AS usuarios FROM app_users GROUP BY status ORDER BY status;

ROLLBACK;
