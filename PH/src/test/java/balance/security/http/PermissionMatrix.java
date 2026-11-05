package balance.security.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Matriz de permisos: rol × endpoint × resultado esperado, dentro de la empresa A. La recorre
 * {@link PermissionMatrixIT}; {@link EndpointCoverageTest} exige que todo endpoint de negocio
 * tenga al menos una fila (o esté en {@link #EXCLUIDOS} con su motivo).
 *
 * <p>Roles (fixture.sql), todos de la empresa A:
 * <ul>
 *   <li><b>root</b>: rol "root" de Keycloak. Pasa siempre (sin fila de Role).</li>
 *   <li><b>encargado</b>: "staff" + Role nivel 1 con gestionar usuarios y los 9 módulos, solo el local 1.</li>
 *   <li><b>cajero</b>: "staff" + Role nivel 3 con POS + SALES_HISTORY, solo el local 1.</li>
 *   <li><b>contador</b>: "staff" + Role nivel 2 con DASHBOARD, TRANSACTIONS, SALARY_PAYMENTS,
 *       SUPPLIER_PAYMENTS y OPERATIONS, sin gestionar usuarios, solo el local 1.</li>
 * </ul>
 * El local 3 (A Norte) no está asignado a nadie acotado: solo root lo ve. El turno 1 (abierto) es
 * del cajero; el turno 3 (cerrado) es del encargado.
 *
 * <p>Celdas: {@code OK} = 2xx, {@code 403} = 403, {@code -} = no se prueba para ese rol (motivo
 * en la columna "nota"). Ninguna celda admite 5xx.
 */
final class PermissionMatrix {

    private PermissionMatrix() {}

    enum Rol {
        ROOT(HttpSecurityTestBase.Usuario.ROOT_A),
        ENCARGADO(HttpSecurityTestBase.Usuario.ENCARGADO_A),
        CAJERO(HttpSecurityTestBase.Usuario.CAJERO_A),
        CONTADOR(HttpSecurityTestBase.Usuario.CONTADOR_A);

        final HttpSecurityTestBase.Usuario usuario;

        Rol(HttpSecurityTestBase.Usuario usuario) {
            this.usuario = usuario;
        }
    }

    record Fila(String metodo, String ruta, String body, Map<Rol, String> esperado, String nota) {}

    record Celda(Rol rol, Fila fila) {
        String esperado() {
            return fila.esperado().get(rol);
        }

        @Override
        public String toString() {
            return rol + " " + fila.metodo() + " " + fila.ruta() + " -> " + esperado();
        }
    }

    private static final String HOY = "2026-11-03";
    private static final String F = "startDate=2020-01-01&endDate=2100-01-01";
    private static final String R = "from=2020-01-01&to=2100-01-01";

    /** Bodies referenciados desde la tabla como $NOMBRE. */
    static final Map<String, String> BODIES = Map.ofEntries(
            Map.entry("$PNG", HttpSecurityTestBase.MULTIPART_PNG),
            Map.entry("$VENTA", "{\"username\":\"x\",\"items\":[{\"productId\":1,\"quantity\":1}]}"),
            Map.entry("$EGRESO", "{\"amount\":5,\"reason\":\"x\",\"username\":\"x\"}"),
            Map.entry("$CIERRE_TURNO", "{\"openingCash\":0,\"declaredCash\":0}"),
            Map.entry("$ENTRADA", "{\"productId\":1,\"type\":\"ENTRADA\",\"quantity\":5}"),
            Map.entry("$SALIDA", "{\"productId\":1,\"type\":\"SALIDA\",\"quantity\":1}"),
            Map.entry("$CATEGORIA", "{\"name\":\"Nueva\"}"),
            Map.entry("$PRODUCTO", "{\"name\":\"Nuevo\",\"price\":1}"),
            Map.entry("$LOCAL", "{\"name\":\"Local nuevo\"}"),
            Map.entry("$MOVIMIENTO", "{\"type\":\"income\",\"amount\":5,\"date\":\"" + HOY + "\",\"description\":\"x\",\"store\":{\"id\":1}}"),
            Map.entry("$CIERRE", "{\"store\":{\"id\":1},\"amount\":5,\"closingsCount\":1,\"depositDate\":\"" + HOY
                    + "\",\"periodStart\":\"" + HOY + "\",\"periodEnd\":\"" + HOY + "\",\"username\":\"x\"}"),
            Map.entry("$GASTO", "{\"fecha\":\"" + HOY + "\",\"monto\":10,\"descripcion\":\"x\",\"tipo\":\"expense\","
                    + "\"distribuciones\":[{\"storeId\":1,\"porcentaje\":100}]}"),
            Map.entry("$PAGO_PROVEEDOR", "{\"store\":{\"id\":1},\"amount\":5,\"paymentDate\":\"" + HOY
                    + "\",\"supplier\":\"x\",\"description\":\"x\",\"username\":\"x\"}"),
            Map.entry("$SUELDO", "{\"store\":{\"id\":1},\"amount\":5,\"salaryDate\":\"" + HOY
                    + "\",\"description\":\"x\",\"username\":\"x\"}"),
            Map.entry("$OPERACION", "{\"amount\":5,\"username\":\"x\",\"description\":\"x\",\"date\":\"" + HOY + "\"}"),
            Map.entry("$DEPOSITO", "{\"storeId\":1,\"periodStart\":\"2020-01-01\",\"periodEnd\":\"2100-01-01\","
                    + "\"declaredAmount\":5,\"username\":\"x\"}"),
            Map.entry("$DEPOSITO_EDIT", "{\"notes\":\"editado\"}"),
            Map.entry("$IMPUESTO", "{\"name\":\"Nuevo\",\"rate\":1,\"type\":\"PERCENTAGE\",\"appliesTo\":\"ALL\"}"),
            Map.entry("$CONFIG", "{\"companyName\":\"Empresa A editada\"}"),
            Map.entry("$EMPRESA", "{\"name\":\"Otra\",\"slug\":\"otra\",\"currency\":\"ARS\",\"timezone\":\"UTC\","
                    + "\"rootUsername\":\"root.otra\",\"rootPassword\":\"Clave123!\",\"rootFullName\":\"Root Otra\"}"),
            Map.entry("$EMPRESA_EDIT", "{\"name\":\"Empresa A\",\"slug\":\"empresa-a\"}"),
            Map.entry("$USUARIO", "{\"fullName\":\"Nuevo\",\"username\":\"nuevo.a\",\"password\":\"Clave123!\",\"roleId\":2,\"storeIds\":[1]}"),
            Map.entry("$ROL", "{\"name\":\"Nuevo\",\"level\":5,\"canManageUsers\":false,\"permissions\":[\"POS\"]}"),
            Map.entry("$ROL_EDIT", "{\"name\":\"Sin uso\",\"level\":4,\"canManageUsers\":false,\"permissions\":[\"POS\"]}")
    );

    private static final String KC = "llama a la API admin de Keycloak (no hay Keycloak en el test)";
    private static final String R2 = "si pasa el permiso sube a R2 (no hay R2 en el test): solo se prueban los 403";

    /**
     * MÉTODO | ruta | body | root | encargado | cajero | contador | nota
     */
    private static final String TABLA = """
            # ── Ventas y turnos (POS para operar; alcance de lectura según rol) ──
            GET    | /api/v2/shifts/1/sales                     | -              | OK  | OK  | OK  | 403 |
            GET    | /api/v2/shifts/3/sales                     | -              | OK  | OK  | 403 | 403 | turno cerrado del encargado
            GET    | /api/v2/sales/1                            | -              | OK  | OK  | OK  | 403 |
            GET    | /api/v2/sales/3                            | -              | OK  | OK  | 403 | 403 | venta de un turno cerrado ajeno
            GET    | /api/v2/shifts/1/summary                   | -              | OK  | OK  | OK  | 403 |
            GET    | /api/v2/stores/1/sales?%R                  | -              | OK  | OK  | OK  | 403 |
            GET    | /api/v2/stores/3/sales                     | -              | OK  | 403 | 403 | 403 | local no asignado
            GET    | /api/v2/stores/1/sales/summary             | -              | OK  | OK  | OK  | 403 |
            GET    | /api/v2/shifts/1/expenses                  | -              | OK  | OK  | OK  | 403 |
            POST   | /api/v2/shifts/1/sales                     | $VENTA         | OK  | OK  | OK  | 403 |
            POST   | /api/v2/shifts/1/sales/quote               | $VENTA         | OK  | OK  | OK  | 403 |
            DELETE | /api/v2/sales/1                            | -              | OK  | OK  | OK  | 403 |
            POST   | /api/v2/shifts/1/expenses                  | $EGRESO        | OK  | OK  | OK  | 403 |
            POST   | /api/v2/shifts/1/closing                   | $CIERRE_TURNO  | OK  | OK  | OK  | 403 |
            PUT    | /api/v2/shifts/1/close                     | -              | OK  | OK  | OK  | 403 |
            POST   | /api/v2/stores/3/shifts                    | {}             | OK  | 403 | 403 | 403 | local no asignado
            GET    | /api/v2/shifts/active/1                    | -              | OK  | OK  | OK  | 403 |
            GET    | /api/v2/stores/1/shifts                    | -              | OK  | OK  | OK  | 403 |
            GET    | /api/v2/shifts/1                           | -              | OK  | OK  | OK  | 403 |
            GET    | /api/v2/shifts/3                           | -              | OK  | OK  | 403 | 403 | turno cerrado del encargado

            # ── Stock (INVENTORY; salida/ajuste solo encargado) ──
            GET    | /api/v2/stores/1/stock                     | -              | OK  | OK  | OK  | 403 | el POS vende sobre el stock de su local
            GET    | /api/v2/stores/1/stock/low                 | -              | OK  | OK  | 403 | 403 |
            GET    | /api/v2/stores/1/stock/summary             | -              | OK  | OK  | 403 | 403 |
            GET    | /api/v2/stores/1/stock/movements           | -              | OK  | OK  | 403 | 403 |
            GET    | /api/v2/stores/3/stock                     | -              | OK  | 403 | 403 | 403 | local no asignado
            POST   | /api/v2/stores/1/stock/adjustment          | $ENTRADA       | OK  | OK  | 403 | 403 |
            POST   | /api/v2/stores/1/stock/adjustment          | $SALIDA        | OK  | OK  | 403 | 403 |

            # ── Catálogo (CATALOG; escribir solo encargado) ──
            GET    | /api/v2/stores/1/categories                | -              | OK  | OK  | OK  | 403 | el POS filtra por categoría
            GET    | /api/v2/stores/3/categories                | -              | OK  | 403 | 403 | 403 | local no asignado
            GET    | /api/v2/categories/1                       | -              | OK  | OK  | 403 | 403 |
            POST   | /api/v2/stores/1/categories                | $CATEGORIA     | OK  | OK  | 403 | 403 |
            POST   | /api/v2/stores/1/categories/1/children     | $CATEGORIA     | OK  | OK  | 403 | 403 |
            PUT    | /api/v2/categories/1                       | $CATEGORIA     | OK  | OK  | 403 | 403 |
            PUT    | /api/v2/categories/1/toggle                | -              | OK  | OK  | 403 | 403 |
            DELETE | /api/v2/categories/4                       | -              | OK  | OK  | 403 | 403 |
            GET    | /api/v2/stores/1/products                  | -              | OK  | OK  | 403 | 403 |
            GET    | /api/v2/products/1                         | -              | OK  | OK  | 403 | 403 |
            POST   | /api/v2/stores/1/products                  | $PRODUCTO      | OK  | OK  | 403 | 403 |
            PUT    | /api/v2/products/1                         | $PRODUCTO      | OK  | OK  | 403 | 403 |
            PUT    | /api/v2/products/1/toggle                  | -              | OK  | OK  | 403 | 403 |
            DELETE | /api/v2/products/4                         | -              | OK  | OK  | 403 | 403 |

            # ── Locales (todos ven los suyos; crear/editar/borrar solo root) ──
            GET    | /api/v2/stores                             | -              | OK  | OK  | OK  | OK  |
            GET    | /api/v2/stores/active                      | -              | OK  | OK  | OK  | OK  |
            GET    | /api/v2/stores/1                           | -              | OK  | OK  | OK  | OK  |
            GET    | /api/v2/stores/3                           | -              | OK  | 403 | 403 | 403 | local no asignado
            POST   | /api/v2/stores                             | $LOCAL         | OK  | 403 | 403 | 403 |
            PUT    | /api/v2/stores/1                           | $LOCAL         | OK  | 403 | 403 | 403 |
            PUT    | /api/v2/stores/1/toggle                    | -              | OK  | 403 | 403 | 403 |
            DELETE | /api/v2/stores/4                           | -              | OK  | 403 | 403 | 403 |

            # ── Finanzas (TRANSACTIONS/SALARY_PAYMENTS/SUPPLIER_PAYMENTS) ──
            POST   | /transactions                              | $MOVIMIENTO    | OK  | OK  | 403 | OK  |
            GET    | /transactions/balance?%F                   | -              | OK  | OK  | 403 | OK  |
            GET    | /transactions                              | -              | OK  | OK  | 403 | OK  |
            PUT    | /transactions/1                            | $MOVIMIENTO    | OK  | OK  | 403 | OK  | el contador edita (P12: falta "solo lectura")
            DELETE | /transactions/1                            | -              | OK  | OK  | 403 | OK  | el contador borra (P12: falta "solo lectura")
            GET    | /api/transactions/store/1                  | -              | OK  | OK  | 403 | OK  |

            # ── Formularios de operaciones (OPERATIONS; gasto/pagos/sueldos solo encargado) ──
            POST   | /api/forms/closing-deposits                | $CIERRE        | OK  | OK  | 403 | OK  |
            GET    | /api/forms/closing-deposits/all            | -              | OK  | OK  | 403 | OK  |
            GET    | /api/forms/closing-deposits?%F             | -              | OK  | OK  | 403 | OK  |
            GET    | /api/forms/closing-deposits/store/1        | -              | OK  | OK  | 403 | OK  |
            POST   | /api/forms/gasto-admin                     | $GASTO         | OK  | OK  | 403 | 403 |
            PUT    | /api/forms/gasto-admin/1                   | $GASTO         | OK  | OK  | 403 | 403 |
            GET    | /api/forms/gasto-admin/all                 | -              | OK  | OK  | 403 | OK  |
            GET    | /api/forms/gasto-admin?%F                  | -              | OK  | OK  | 403 | OK  |
            POST   | /api/forms/supplier-payments               | $PAGO_PROVEEDOR| OK  | OK  | 403 | 403 |
            GET    | /api/forms/supplier-payments/all           | -              | OK  | OK  | 403 | OK  |
            GET    | /api/forms/supplier-payments?%F            | -              | OK  | OK  | 403 | OK  |
            POST   | /api/forms/salary-payments                 | $SUELDO        | OK  | OK  | 403 | 403 |
            GET    | /api/forms/salary-payments                 | -              | OK  | OK  | 403 | OK  |

            # ── Operaciones (OPERATIONS; editar/borrar solo encargado) ──
            GET    | /api/operations/all                        | -              | OK  | OK  | 403 | OK  |
            GET    | /api/operations/all?storeId=1              | -              | OK  | OK  | 403 | OK  |
            GET    | /api/operations/all?storeId=3              | -              | OK  | 403 | 403 | 403 | local no asignado
            GET    | /api/operations/mine                       | -              | OK  | OK  | 403 | OK  |
            GET    | /api/operations/mine?username=cajero.a     | -              | OK  | OK  | 403 | 403 | operaciones de otro: solo quien gestiona usuarios
            PUT    | /api/operations/CLOSING/1                  | $OPERACION     | OK  | OK  | 403 | 403 |
            PUT    | /api/operations/SUPPLIER/1                 | $OPERACION     | OK  | OK  | 403 | 403 |
            PUT    | /api/operations/SALARY/1                   | $OPERACION     | OK  | OK  | 403 | 403 |
            PUT    | /api/operations/GASTO_ADMIN/1              | $OPERACION     | OK  | OK  | 403 | 403 |
            DELETE | /api/operations/CLOSING/1                  | -              | OK  | OK  | 403 | 403 |
            DELETE | /api/operations/SUPPLIER/1                 | -              | OK  | OK  | 403 | 403 |
            DELETE | /api/operations/SALARY/1                   | -              | OK  | OK  | 403 | 403 |
            DELETE | /api/operations/GASTO_ADMIN/1              | -              | OK  | OK  | 403 | 403 |

            # ── Operaciones v3 e informes (sin local = todos: solo root) ──
            GET    | /api/v3/operations?storeId=1&%R            | -              | OK  | OK  | 403 | OK  |
            GET    | /api/v3/operations?%R                      | -              | OK  | 403 | 403 | 403 | agregado sin local: falla cerrado para acotados
            GET    | /api/v3/operations/summary?storeId=1       | -              | OK  | OK  | 403 | OK  |
            GET    | /api/v3/operations/export?format=CSV&storeId=1 | -          | OK  | OK  | 403 | OK  |
            GET    | /api/v3/reports/sales?format=CSV&storeId=1 | -              | OK  | OK  | OK  | 403 |
            GET    | /api/v3/reports/sales?format=CSV           | -              | OK  | 403 | 403 | 403 | agregado sin local
            GET    | /api/v3/reports/products-sold?format=CSV&storeId=1 | -      | OK  | OK  | OK  | 403 |

            # ── Depósitos bancarios (OPERATIONS; editar/borrar solo encargado) ──
            GET    | /api/v3/deposits/pending-closings?storeId=1&periodStart=2020-01-01&periodEnd=2100-01-01 | - | OK | OK | 403 | OK |
            POST   | /api/v3/deposits                           | $DEPOSITO      | OK  | OK  | 403 | OK  |
            GET    | /api/v3/deposits                           | -              | OK  | OK  | 403 | OK  |
            GET    | /api/v3/deposits?storeId=1                 | -              | OK  | OK  | 403 | OK  |
            GET    | /api/v3/deposits/1                         | -              | OK  | OK  | 403 | OK  |
            PUT    | /api/v3/deposits/1                         | $DEPOSITO_EDIT | OK  | OK  | 403 | 403 |
            DELETE | /api/v3/deposits/1                         | -              | OK  | OK  | 403 | 403 |

            # ── Dashboard, impuestos y configuración ──
            GET    | /api/v2/dashboard                          | -              | OK  | OK  | 403 | OK  |
            GET    | /api/v3/taxes                              | -              | OK  | OK  | OK  | 403 | CATALOG o POS
            GET    | /api/v3/taxes/active                       | -              | OK  | OK  | OK  | 403 | CATALOG o POS
            POST   | /api/v3/taxes                              | $IMPUESTO      | OK  | 403 | 403 | 403 |
            PUT    | /api/v3/taxes/1                            | $IMPUESTO      | OK  | 403 | 403 | 403 |
            PUT    | /api/v3/taxes/1/toggle                     | -              | OK  | 403 | 403 | 403 |
            DELETE | /api/v3/taxes/1                            | -              | OK  | 403 | 403 | 403 |
            GET    | /api/v3/tenant/config                      | -              | OK  | OK  | OK  | OK  |
            PUT    | /api/v3/tenant/config                      | $CONFIG        | OK  | 403 | 403 | 403 |

            # ── Empresas (solo plataforma; cada uno puede leer la fila de su empresa) ──
            GET    | /api/v3/tenants                            | -              | 403 | 403 | 403 | 403 |
            GET    | /api/v3/tenants/active                     | -              | 403 | 403 | 403 | 403 |
            GET    | /api/v3/tenants/1                          | -              | OK  | OK  | OK  | OK  | su propia empresa
            GET    | /api/v3/tenants/slug/empresa-a             | -              | 403 | 403 | 403 | 403 |
            POST   | /api/v3/tenants                            | $EMPRESA       | 403 | 403 | 403 | 403 |
            PUT    | /api/v3/tenants/1                          | $EMPRESA_EDIT  | 403 | 403 | 403 | 403 |
            PUT    | /api/v3/tenants/1/toggle                   | -              | 403 | 403 | 403 | 403 |
            DELETE | /api/v3/tenants/1                          | -              | 403 | 403 | 403 | 403 |

            # ── Usuarios y roles (gestionar usuarios; roles solo root) ──
            GET    | /api/v2/users                              | -              | OK  | OK  | 403 | 403 |
            GET    | /api/v2/users/by-username/cajero.a         | -              | OK  | OK  | OK  | 403 | el propio perfil o quien gestiona
            GET    | /api/v2/users/by-username/encargado.a      | -              | OK  | OK  | 403 | 403 |
            GET    | /api/v2/users/store/1                      | -              | OK  | OK  | 403 | 403 |
            POST   | /api/v2/users                              | $USUARIO       | -   | -   | 403 | 403 | %KC
            PUT    | /api/v2/users/3/suspend                    | -              | -   | -   | 403 | 403 | %KC
            PUT    | /api/v2/users/3/activate                   | -              | -   | -   | 403 | 403 | %KC
            PUT    | /api/v2/users/3/reassign                   | {"storeId":1}  | OK  | OK  | 403 | 403 |
            PUT    | /api/v2/users/3/role                       | {"roleId":2}   | OK  | OK  | 403 | 403 |
            PUT    | /api/v2/users/3/permissions                | {"permissions":["POS"]} | OK | OK | 403 | 403 |
            PUT    | /api/v2/users/3/store-access               | {"storeIds":[1]} | OK | OK  | 403 | 403 |
            PUT    | /api/v2/users/3/reset-password             | {"password":"Clave123!"} | - | - | 403 | 403 | %KC
            DELETE | /api/v2/users/3                            | -              | -   | -   | 403 | 403 | %KC
            GET    | /api/v2/roles                              | -              | OK  | OK  | 403 | 403 |
            POST   | /api/v2/roles                              | $ROL           | OK  | 403 | 403 | 403 |
            PUT    | /api/v2/roles/5                            | $ROL_EDIT      | OK  | 403 | 403 | 403 |
            DELETE | /api/v2/roles/5                            | -              | OK  | 403 | 403 | 403 |

            # ── Comprobantes (OPERATIONS/TRANSACTIONS/SALARY_PAYMENTS/SUPPLIER_PAYMENTS en el local) ──
            GET    | /api/v2/uploads/comprobante/url?ref=test/empresa-a/comprobantes/a.png | - | OK | OK | 403 | OK | adjunto a la transacción 1 (local 1)
            POST   | /api/v2/uploads/comprobante?storeId=1      | $PNG           | -   | -   | 403 | -   | %R2
            POST   | /api/v2/uploads/comprobante?storeId=3      | $PNG           | -   | 403 | 403 | 403 | %R2
            """;

    static final List<Fila> FILAS = parsear(TABLA);

    /** Endpoints de negocio sin fila en la matriz, con el motivo. Hoy ninguno. */
    static final Map<String, String> EXCLUIDOS = Map.of();

    private static List<Fila> parsear(String tabla) {
        List<Fila> filas = new ArrayList<>();
        for (String linea : tabla.split("\n")) {
            String l = linea.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] c = l.split("\\|", -1);
            if (c.length != 8) {
                throw new IllegalStateException("Fila mal formada (8 columnas): " + l);
            }
            String ruta = c[1].strip().replace("%R", R).replace("%F", F);
            String body = c[2].strip();
            body = body.equals("-") ? null : BODIES.getOrDefault(body, body);
            if (body != null && body.startsWith("$")) {
                throw new IllegalStateException("Body desconocido: " + body);
            }
            Map<Rol, String> esperado = Map.of(
                    Rol.ROOT, celda(c[3]), Rol.ENCARGADO, celda(c[4]), Rol.CAJERO, celda(c[5]), Rol.CONTADOR, celda(c[6]));
            filas.add(new Fila(c[0].strip(), ruta, body, esperado, c[7].strip().replace("%KC", KC).replace("%R2", R2)));
        }
        return List.copyOf(filas);
    }

    private static String celda(String v) {
        String s = v.strip();
        if (!s.equals("OK") && !s.equals("403") && !s.equals("-")) {
            throw new IllegalStateException("Celda inválida: '" + s + "' (OK, 403 o -)");
        }
        return s;
    }
}
