-- Mejora continua v4 (k6): ids que necesitan los escenarios (solo ids sinteticos, sin claves ni datos personales).
-- levantar.sh lo corre con psql -At y lo guarda en tests/load/.generado/datos.json.
SELECT json_build_object(
    'empresas', (
        SELECT json_agg(json_build_object(
                   'tenant', t.id,
                   'slug', t.slug,
                   'locales', (
                       SELECT json_agg(json_build_object(
                                  'id', s.id,
                                  'turno', (SELECT sh.id FROM shifts sh WHERE sh.store_id = s.id AND sh.status = 'OPEN'),
                                  'productos', (SELECT json_agg(p.id ORDER BY p.id) FROM products p WHERE p.store_id = s.id)
                              ) ORDER BY s.id)
                       FROM store s WHERE s.tenant_id = t.id AND s.id < 90)
               ) ORDER BY t.id)
        FROM tenants t WHERE t.slug LIKE 'carga-%'),
    'carrera', (
        SELECT json_build_object('tenant', 1, 'local', 91, 'producto', 91001,
                                 'turno', (SELECT id FROM shifts WHERE store_id = 91 AND status = 'OPEN'),
                                 'stock', (SELECT quantity FROM inventory_stock WHERE product_id = 91001))),
    'idempotencia', (
        SELECT json_build_object('tenant', 1, 'local', 92, 'producto', 92001,
                                 'turno', (SELECT id FROM shifts WHERE store_id = 92 AND status = 'OPEN'))),
    'turnos', json_build_object('tenant', 1, 'local', 93),
    'ajuste', (
        SELECT json_build_object('tenant', 1, 'local', 94, 'producto', 94001,
                                 'turno', (SELECT id FROM shifts WHERE store_id = 94 AND status = 'OPEN'),
                                 'stock', (SELECT quantity FROM inventory_stock WHERE product_id = 94001)))
);
