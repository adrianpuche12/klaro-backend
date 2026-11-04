package balance.security.http;

import java.util.List;
import java.util.Map;

/**
 * Casos del test entre empresas ({@link CrossTenantHttpIT}): con un token de la empresa A,
 * cada endpoint de negocio apuntado a datos de la empresa B (ids de fixture.sql).
 *
 * <p>Cada caso declara el endpoint que cubre ("MÉTODO /ruta/{var}", igual que en el controller).
 * {@link EndpointCoverageTest} recorre por reflexión los {@code @RequestMapping} de los
 * controllers y falla si aparece un endpoint que no está ni acá ni en {@link #EXCLUIDOS}:
 * un endpoint nuevo no se puede mergear sin decidir cómo se prueba su aislamiento.
 *
 * <p>Ids de la empresa B: local 2, categoría 2, producto 2, turno 2, venta 2, depósito 2,
 * transacción 2 (y 4, de gasto 2), cierre 2, pago a proveedor 2, sueldo 2, gasto admin 2,
 * impuesto 2, rol 4, usuario 6 (cajero.secretob). Los casos "mixtos" usan un recurso de A
 * con una referencia a B en el body o la ruta (ej. vender un producto de B en un turno de A).
 */
final class CrossTenantCatalog {

    private CrossTenantCatalog() {}

    enum Esperado {
        /** 403 o 404: el recurso de B no existe para A. */
        RECHAZO,
        /**
         * 400, 403 o 404. Solo para endpoints cuyo contrato existente responde 400 a un id
         * inexistente (capturan IllegalArgumentException); cada uso lleva su motivo.
         */
        RECHAZO_O_400,
        /**
         * 403, 404 o 200 con una lista vacía: listados "por id" que filtran por tenant y, si el
         * padre no es de la empresa, devuelven [] en vez de 404. No filtran, pero no es lo ideal.
         */
        RECHAZO_O_VACIO,
        /** 2xx sin ningún dato de B: endpoints que no reciben ids (listados, alta propia). */
        SOLO_PROPIOS
    }

    record Caso(String endpoint, String ruta, String body, Esperado esperado, String nota) {
        String metodo() {
            return endpoint.substring(0, endpoint.indexOf(' '));
        }

        @Override
        public String toString() {
            return metodo() + " " + ruta + (nota.isEmpty() ? "" : " [" + nota + "]");
        }
    }

    private static final String DESDE = "2020-01-01";
    private static final String HASTA = "2100-01-01";
    private static final String HOY = "2026-11-03";
    private static final String FECHAS = "startDate=" + DESDE + "&endDate=" + HASTA;

    private static Caso rechazo(String endpoint, String ruta) {
        return new Caso(endpoint, ruta, null, Esperado.RECHAZO, "");
    }

    private static Caso rechazo(String endpoint, String ruta, String body) {
        return new Caso(endpoint, ruta, body, Esperado.RECHAZO, "");
    }

    private static Caso mixto(String endpoint, String ruta, String body, String nota) {
        return new Caso(endpoint, ruta, body, Esperado.RECHAZO, "mixto: " + nota);
    }

    private static Caso rechazo400(String endpoint, String ruta, String body, String motivo) {
        return new Caso(endpoint, ruta, body, Esperado.RECHAZO_O_400, motivo);
    }

    /** El controller captura IllegalArgumentException y responde 400 "no encontrado" (contrato existente). */
    private static final String NF400 = "400 'no encontrado': el controller captura IllegalArgumentException";
    private static final String VACIO = "lista filtrada por tenant: [] si el turno no es de la empresa";

    private static Caso rechazo400n(String endpoint, String ruta) {
        return new Caso(endpoint, ruta, null, Esperado.RECHAZO_O_400, NF400);
    }

    private static Caso rechazo400n(String endpoint, String ruta, String body) {
        return new Caso(endpoint, ruta, body, Esperado.RECHAZO_O_400, NF400);
    }

    private static Caso mixto400(String endpoint, String ruta, String body, String nota) {
        return new Caso(endpoint, ruta, body, Esperado.RECHAZO_O_400, "mixto: " + nota + "; " + NF400);
    }

    private static Caso rechazoOVacio(String endpoint, String ruta, String motivo) {
        return new Caso(endpoint, ruta, null, Esperado.RECHAZO_O_VACIO, motivo);
    }

    private static Caso propios(String endpoint, String ruta) {
        return new Caso(endpoint, ruta, null, Esperado.SOLO_PROPIOS, "");
    }

    private static Caso propios(String endpoint, String ruta, String body) {
        return new Caso(endpoint, ruta, body, Esperado.SOLO_PROPIOS, "");
    }

    private static final String VENTA_PRODUCTO_B = """
            {"username":"x","items":[{"productId":2,"quantity":1}]}""";
    private static final String CATEGORIA = """
            {"name":"Nueva"}""";
    private static final String PRODUCTO = """
            {"name":"Nuevo","price":1}""";
    private static final String MOVIMIENTO_STORE_B = """
            {"type":"income","amount":5,"date":"%s","description":"x","store":{"id":2}}""".formatted(HOY);
    private static final String CIERRE_STORE_B = """
            {"store":{"id":2},"amount":5,"closingsCount":1,"depositDate":"%s","periodStart":"%s","periodEnd":"%s","username":"x"}"""
            .formatted(HOY, HOY, HOY);
    private static final String PAGO_PROVEEDOR_STORE_B = """
            {"store":{"id":2},"amount":5,"paymentDate":"%s","supplier":"x","description":"x","username":"x"}""".formatted(HOY);
    private static final String SUELDO_STORE_B = """
            {"store":{"id":2},"amount":5,"salaryDate":"%s","description":"x","username":"x"}""".formatted(HOY);
    private static String gasto(long storeId) {
        return """
                {"fecha":"%s","monto":10,"descripcion":"x","tipo":"expense","distribuciones":[{"storeId":%d,"porcentaje":100}]}"""
                .formatted(HOY, storeId);
    }
    private static String operacion(Long storeId) {
        return """
                {"amount":5,"username":"x","description":"x","date":"%s"%s}"""
                .formatted(HOY, storeId == null ? "" : ",\"storeId\":" + storeId);
    }
    private static final String DEPOSITO_STORE_B = """
            {"storeId":2,"periodStart":"%s","periodEnd":"%s","declaredAmount":5,"username":"x"}""".formatted(DESDE, HASTA);

    private static final String USUARIO_400 =
            "AppUserController responde 400 'Usuario no encontrado' (captura IllegalArgumentException)";
    private static final String ROL_400 =
            "RoleController responde 400 'Rol no encontrado' (captura IllegalArgumentException)";

    static final List<Caso> CASOS = List.of(
            // ── Ventas (SalesController) ──────────────────────────────────────
            rechazo400n("POST /api/v2/shifts/{shiftId}/sales", "/api/v2/shifts/2/sales", VENTA_PRODUCTO_B),
            mixto400("POST /api/v2/shifts/{shiftId}/sales", "/api/v2/shifts/1/sales", VENTA_PRODUCTO_B,
                    "producto de B en un turno de A"),
            rechazo400n("POST /api/v2/shifts/{shiftId}/sales/quote", "/api/v2/shifts/2/sales/quote", VENTA_PRODUCTO_B),
            mixto400("POST /api/v2/shifts/{shiftId}/sales/quote", "/api/v2/shifts/1/sales/quote", VENTA_PRODUCTO_B,
                    "cotizar un producto de B en un turno de A"),
            rechazo("GET /api/v2/shifts/{shiftId}/sales", "/api/v2/shifts/2/sales"),
            rechazo("GET /api/v2/sales/{saleId}", "/api/v2/sales/2"),
            rechazo400n("DELETE /api/v2/sales/{saleId}", "/api/v2/sales/2"),
            rechazo400n("GET /api/v2/shifts/{shiftId}/summary", "/api/v2/shifts/2/summary"),
            rechazo("GET /api/v2/stores/{storeId}/sales", "/api/v2/stores/2/sales?from=" + DESDE + "&to=" + HASTA),
            rechazo400n("GET /api/v2/stores/{storeId}/sales/summary", "/api/v2/stores/2/sales/summary"),
            rechazo400n("POST /api/v2/shifts/{shiftId}/closing", "/api/v2/shifts/2/closing",
                    "{\"openingCash\":0,\"declaredCash\":0}"),
            rechazo400n("POST /api/v2/shifts/{shiftId}/expenses", "/api/v2/shifts/2/expenses",
                    "{\"amount\":5,\"reason\":\"x\",\"username\":\"x\"}"),
            rechazoOVacio("GET /api/v2/shifts/{shiftId}/expenses", "/api/v2/shifts/2/expenses", VACIO),

            // ── Turnos (ShiftController) ──────────────────────────────────────
            rechazo400n("POST /api/v2/stores/{storeId}/shifts", "/api/v2/stores/2/shifts", "{}"),
            rechazo("PUT /api/v2/shifts/{shiftId}/close", "/api/v2/shifts/2/close"),
            rechazo("GET /api/v2/shifts/active/{storeId}", "/api/v2/shifts/active/2"),
            rechazo("GET /api/v2/stores/{storeId}/shifts", "/api/v2/stores/2/shifts"),
            rechazo("GET /api/v2/shifts/{shiftId}", "/api/v2/shifts/2"),

            // ── Stock (InventoryController) ───────────────────────────────────
            rechazo("GET /api/v2/stores/{storeId}/stock", "/api/v2/stores/2/stock"),
            rechazo("GET /api/v2/stores/{storeId}/stock/low", "/api/v2/stores/2/stock/low"),
            rechazo("GET /api/v2/stores/{storeId}/stock/summary", "/api/v2/stores/2/stock/summary"),
            rechazo("GET /api/v2/stores/{storeId}/stock/movements", "/api/v2/stores/2/stock/movements"),
            rechazo400n("POST /api/v2/stores/{storeId}/stock/adjustment", "/api/v2/stores/2/stock/adjustment",
                    "{\"productId\":2,\"type\":\"ENTRADA\",\"quantity\":5}"),
            rechazo400n("POST /api/v2/stores/{storeId}/stock/adjustment", "/api/v2/stores/2/stock/adjustment",
                    "{\"productId\":2,\"type\":\"SALIDA\",\"quantity\":5}"),
            mixto400("POST /api/v2/stores/{storeId}/stock/adjustment", "/api/v2/stores/1/stock/adjustment",
                    "{\"productId\":2,\"type\":\"ENTRADA\",\"quantity\":5}", "producto de B en un local de A"),

            // ── Catálogo (CategoryController, ProductController) ──────────────
            rechazo("GET /api/v2/stores/{storeId}/categories", "/api/v2/stores/2/categories"),
            rechazo("GET /api/v2/categories/{id}", "/api/v2/categories/2"),
            rechazo("POST /api/v2/stores/{storeId}/categories", "/api/v2/stores/2/categories", CATEGORIA),
            rechazo("POST /api/v2/stores/{storeId}/categories/{parentId}/children",
                    "/api/v2/stores/2/categories/2/children", CATEGORIA),
            mixto("POST /api/v2/stores/{storeId}/categories/{parentId}/children",
                    "/api/v2/stores/1/categories/2/children", CATEGORIA, "categoría padre de B en un local de A"),
            rechazo("PUT /api/v2/categories/{id}", "/api/v2/categories/2", CATEGORIA),
            rechazo("PUT /api/v2/categories/{id}/toggle", "/api/v2/categories/2/toggle"),
            rechazo("DELETE /api/v2/categories/{id}", "/api/v2/categories/2"),
            rechazo("GET /api/v2/stores/{storeId}/products", "/api/v2/stores/2/products"),
            rechazo("GET /api/v2/products/{id}", "/api/v2/products/2"),
            rechazo400n("POST /api/v2/stores/{storeId}/products", "/api/v2/stores/2/products", PRODUCTO),
            mixto400("POST /api/v2/stores/{storeId}/products", "/api/v2/stores/1/products",
                    "{\"name\":\"Nuevo\",\"price\":1,\"categoryId\":2}", "categoría de B en un producto de A"),
            rechazo("PUT /api/v2/products/{id}", "/api/v2/products/2", PRODUCTO),
            mixto400("PUT /api/v2/products/{id}", "/api/v2/products/1",
                    "{\"name\":\"Cafe A\",\"price\":100,\"categoryId\":2}", "categoría de B en un producto de A"),
            rechazo("PUT /api/v2/products/{id}/toggle", "/api/v2/products/2/toggle"),
            rechazo("DELETE /api/v2/products/{id}", "/api/v2/products/2"),

            // ── Locales (StoreV2Controller) ───────────────────────────────────
            propios("GET /api/v2/stores", "/api/v2/stores"),
            propios("GET /api/v2/stores/active", "/api/v2/stores/active"),
            rechazo("GET /api/v2/stores/{id}", "/api/v2/stores/2"),
            propios("POST /api/v2/stores", "/api/v2/stores", "{\"name\":\"Nuevo A\"}"),
            rechazo("PUT /api/v2/stores/{id}", "/api/v2/stores/2", "{\"name\":\"Pisado\"}"),
            rechazo("PUT /api/v2/stores/{id}/toggle", "/api/v2/stores/2/toggle"),
            rechazo("DELETE /api/v2/stores/{id}", "/api/v2/stores/2"),

            // ── Transacciones (BalanceController, TransactionController) ──────
            rechazo("POST /transactions", "/transactions", MOVIMIENTO_STORE_B),
            propios("GET /transactions/balance", "/transactions/balance?startDate=" + DESDE + "&endDate=" + HASTA),
            propios("GET /transactions", "/transactions"),
            rechazo("PUT /transactions/{id}", "/transactions/2", MOVIMIENTO_STORE_B.replace("{\"id\":2}", "{\"id\":1}")),
            mixto("PUT /transactions/{id}", "/transactions/1", MOVIMIENTO_STORE_B, "mover un movimiento de A a un local de B"),
            rechazo("DELETE /transactions/{id}", "/transactions/2"),
            rechazo("GET /api/transactions/store/{storeId}", "/api/transactions/store/2"),

            // ── Formularios de operaciones (FormsController) ──────────────────
            rechazo("POST /api/forms/closing-deposits", "/api/forms/closing-deposits", CIERRE_STORE_B),
            propios("GET /api/forms/closing-deposits/all", "/api/forms/closing-deposits/all"),
            rechazo("POST /api/forms/gasto-admin", "/api/forms/gasto-admin", gasto(2)),
            rechazo("PUT /api/forms/gasto-admin/{id}", "/api/forms/gasto-admin/2", gasto(1)),
            mixto("PUT /api/forms/gasto-admin/{id}", "/api/forms/gasto-admin/1", gasto(2),
                    "repartir un gasto de A en un local de B"),
            propios("GET /api/forms/gasto-admin/all", "/api/forms/gasto-admin/all"),
            propios("GET /api/forms/gasto-admin", "/api/forms/gasto-admin?" + FECHAS),
            propios("GET /api/forms/closing-deposits", "/api/forms/closing-deposits?" + FECHAS),
            rechazo("GET /api/forms/closing-deposits/store/{storeId}", "/api/forms/closing-deposits/store/2"),
            rechazo("POST /api/forms/supplier-payments", "/api/forms/supplier-payments", PAGO_PROVEEDOR_STORE_B),
            propios("GET /api/forms/supplier-payments/all", "/api/forms/supplier-payments/all"),
            propios("GET /api/forms/supplier-payments", "/api/forms/supplier-payments?" + FECHAS),
            rechazo("POST /api/forms/salary-payments", "/api/forms/salary-payments", SUELDO_STORE_B),
            propios("GET /api/forms/salary-payments", "/api/forms/salary-payments"),

            // ── Operaciones (OperationsController) ────────────────────────────
            propios("GET /api/operations/all", "/api/operations/all"),
            rechazo("GET /api/operations/all", "/api/operations/all?storeId=2"),
            propios("GET /api/operations/mine", "/api/operations/mine"),
            propios("GET /api/operations/mine", "/api/operations/mine?username=cajero.secretob"),
            rechazo("PUT /api/operations/{type}/{id}", "/api/operations/CLOSING/2", operacion(null)),
            rechazo("PUT /api/operations/{type}/{id}", "/api/operations/SUPPLIER/2", operacion(null)),
            rechazo("PUT /api/operations/{type}/{id}", "/api/operations/SALARY/2", operacion(null)),
            rechazo("PUT /api/operations/{type}/{id}", "/api/operations/GASTO_ADMIN/2", operacion(null)),
            mixto("PUT /api/operations/{type}/{id}", "/api/operations/CLOSING/1", operacion(2L),
                    "mover un cierre de A a un local de B"),
            mixto("PUT /api/operations/{type}/{id}", "/api/operations/SUPPLIER/1", operacion(2L),
                    "mover un pago de A a un local de B"),
            mixto("PUT /api/operations/{type}/{id}", "/api/operations/SALARY/1", operacion(2L),
                    "mover un sueldo de A a un local de B"),
            rechazo("DELETE /api/operations/{type}/{id}", "/api/operations/CLOSING/2"),
            rechazo("DELETE /api/operations/{type}/{id}", "/api/operations/SUPPLIER/2"),
            rechazo("DELETE /api/operations/{type}/{id}", "/api/operations/SALARY/2"),
            rechazo("DELETE /api/operations/{type}/{id}", "/api/operations/GASTO_ADMIN/2"),

            // ── Operaciones v3 e informes (OperationsV3Controller, ReportsController) ──
            propios("GET /api/v3/operations", "/api/v3/operations?from=" + DESDE + "&to=" + HASTA),
            rechazo("GET /api/v3/operations", "/api/v3/operations?storeId=2&from=" + DESDE + "&to=" + HASTA),
            propios("GET /api/v3/operations/summary", "/api/v3/operations/summary?from=" + DESDE + "&to=" + HASTA),
            rechazo("GET /api/v3/operations/summary", "/api/v3/operations/summary?storeId=2"),
            propios("GET /api/v3/operations/export", "/api/v3/operations/export?format=CSV&from=" + DESDE + "&to=" + HASTA),
            rechazo("GET /api/v3/operations/export", "/api/v3/operations/export?format=CSV&storeId=2"),
            propios("GET /api/v3/reports/sales", "/api/v3/reports/sales?format=CSV&from=" + DESDE + "&to=" + HASTA),
            rechazo("GET /api/v3/reports/sales", "/api/v3/reports/sales?format=CSV&storeId=2"),
            propios("GET /api/v3/reports/products-sold", "/api/v3/reports/products-sold?format=CSV&from=" + DESDE + "&to=" + HASTA),
            rechazo("GET /api/v3/reports/products-sold", "/api/v3/reports/products-sold?format=CSV&storeId=2"),

            // ── Depósitos bancarios (BankDepositController) ───────────────────
            rechazo("GET /api/v3/deposits/pending-closings",
                    "/api/v3/deposits/pending-closings?storeId=2&periodStart=" + DESDE + "&periodEnd=" + HASTA),
            rechazo400n("POST /api/v3/deposits", "/api/v3/deposits", DEPOSITO_STORE_B),
            propios("GET /api/v3/deposits", "/api/v3/deposits"),
            rechazoOVacio("GET /api/v3/deposits", "/api/v3/deposits?storeId=2",
                    "BankDepositService.findAll filtra por tenant: [] si el local no es de la empresa"),
            rechazo("GET /api/v3/deposits/{id}", "/api/v3/deposits/2"),
            rechazo("PUT /api/v3/deposits/{id}", "/api/v3/deposits/2", "{\"notes\":\"pisado\"}"),
            rechazo("DELETE /api/v3/deposits/{id}", "/api/v3/deposits/2"),

            // ── Dashboard, impuestos y configuración ──────────────────────────
            propios("GET /api/v2/dashboard", "/api/v2/dashboard"),
            propios("GET /api/v3/taxes", "/api/v3/taxes"),
            propios("GET /api/v3/taxes/active", "/api/v3/taxes/active"),
            propios("POST /api/v3/taxes", "/api/v3/taxes",
                    "{\"name\":\"Nuevo\",\"rate\":1,\"type\":\"PERCENTAGE\",\"appliesTo\":\"ALL\"}"),
            mixto("POST /api/v3/taxes", "/api/v3/taxes",
                    "{\"name\":\"Nuevo\",\"rate\":1,\"type\":\"PERCENTAGE\",\"appliesTo\":\"PRODUCT\",\"productId\":2}",
                    "impuesto de A sobre un producto de B"),
            rechazo("PUT /api/v3/taxes/{id}", "/api/v3/taxes/2", "{\"name\":\"Pisado\"}"),
            rechazo("PUT /api/v3/taxes/{id}/toggle", "/api/v3/taxes/2/toggle"),
            rechazo("DELETE /api/v3/taxes/{id}", "/api/v3/taxes/2"),
            propios("GET /api/v3/tenant/config", "/api/v3/tenant/config"),
            propios("PUT /api/v3/tenant/config", "/api/v3/tenant/config", "{\"companyName\":\"Empresa A editada\"}"),

            // ── Empresas (TenantController: solo plataforma) ──────────────────
            rechazo("GET /api/v3/tenants", "/api/v3/tenants"),
            rechazo("GET /api/v3/tenants/active", "/api/v3/tenants/active"),
            rechazo("GET /api/v3/tenants/{id}", "/api/v3/tenants/2"),
            rechazo("GET /api/v3/tenants/slug/{slug}", "/api/v3/tenants/slug/empresa-secretob"),
            rechazo("POST /api/v3/tenants", "/api/v3/tenants",
                    "{\"name\":\"Otra\",\"slug\":\"otra\",\"currency\":\"ARS\",\"timezone\":\"America/Argentina/Buenos_Aires\","
                            + "\"rootUsername\":\"root.otra\",\"rootPassword\":\"Clave123!\",\"rootFullName\":\"Root Otra\"}"),
            rechazo("PUT /api/v3/tenants/{id}", "/api/v3/tenants/2", "{\"name\":\"Pisada\",\"slug\":\"pisada\"}"),
            rechazo("PUT /api/v3/tenants/{id}/toggle", "/api/v3/tenants/2/toggle"),
            rechazo("DELETE /api/v3/tenants/{id}", "/api/v3/tenants/2"),

            // ── Usuarios y roles (AppUserController, RoleController) ──────────
            propios("GET /api/v2/users", "/api/v2/users"),
            rechazo("GET /api/v2/users/by-username/{username}", "/api/v2/users/by-username/cajero.secretob"),
            rechazo("GET /api/v2/users/store/{storeId}", "/api/v2/users/store/2"),
            mixto400("POST /api/v2/users", "/api/v2/users",
                    "{\"fullName\":\"Nuevo\",\"username\":\"nuevo.a\",\"password\":\"Clave123!\",\"roleId\":4,\"storeIds\":[2]}",
                    "usuario de A con rol y local de B"),
            rechazo400("PUT /api/v2/users/{id}/suspend", "/api/v2/users/6/suspend", null, USUARIO_400),
            rechazo400("PUT /api/v2/users/{id}/activate", "/api/v2/users/6/activate", null, USUARIO_400),
            rechazo400("PUT /api/v2/users/{id}/reassign", "/api/v2/users/6/reassign", "{\"storeId\":1}", USUARIO_400),
            mixto400("PUT /api/v2/users/{id}/reassign", "/api/v2/users/3/reassign", "{\"storeId\":2}",
                    "usuario de A a un local de B"),
            rechazo400("PUT /api/v2/users/{id}/role", "/api/v2/users/6/role", "{\"roleId\":2}", USUARIO_400),
            mixto400("PUT /api/v2/users/{id}/role", "/api/v2/users/3/role", "{\"roleId\":4}", "usuario de A con rol de B"),
            rechazo400("PUT /api/v2/users/{id}/permissions", "/api/v2/users/6/permissions",
                    "{\"permissions\":[\"POS\"]}", USUARIO_400),
            rechazo400("PUT /api/v2/users/{id}/store-access", "/api/v2/users/6/store-access",
                    "{\"storeIds\":[1]}", USUARIO_400),
            mixto400("PUT /api/v2/users/{id}/store-access", "/api/v2/users/3/store-access", "{\"storeIds\":[2]}",
                    "usuario de A con acceso a un local de B"),
            rechazo400("PUT /api/v2/users/{id}/reset-password", "/api/v2/users/6/reset-password",
                    "{\"password\":\"Clave123!\"}", USUARIO_400),
            rechazo400("DELETE /api/v2/users/{id}", "/api/v2/users/6", null, USUARIO_400),
            propios("GET /api/v2/roles", "/api/v2/roles"),
            propios("POST /api/v2/roles", "/api/v2/roles",
                    "{\"name\":\"Nuevo\",\"level\":5,\"canManageUsers\":false,\"permissions\":[\"POS\"]}"),
            rechazo400("PUT /api/v2/roles/{id}", "/api/v2/roles/4",
                    "{\"name\":\"Pisado\",\"level\":5,\"canManageUsers\":false,\"permissions\":[]}", ROL_400),
            rechazo400("DELETE /api/v2/roles/{id}", "/api/v2/roles/4", null, ROL_400),

            // ── Comprobantes (StorageController) ──────────────────────────────
            // El de B está adjunto a su transacción 2 (fixture.sql). R2 no se llama: se rechaza antes.
            rechazo("POST /api/v2/uploads/comprobante", "/api/v2/uploads/comprobante?storeId=2",
                    HttpSecurityTestBase.MULTIPART_PNG),
            rechazo("GET /api/v2/uploads/comprobante/url",
                    "/api/v2/uploads/comprobante/url?ref=test/empresa-secretob/comprobantes/secretob.png"),
            rechazo("GET /api/v2/uploads/comprobante/url",
                    "/api/v2/uploads/comprobante/url?ref=test/empresa-a/../empresa-secretob/comprobantes/secretob.png")
    );

    /**
     * Endpoints de negocio que no entran en el test entre empresas, con el motivo.
     * Clave: "MÉTODO /ruta/{var}" (igual que en los casos). Hoy ninguno.
     */
    static final Map<String, String> EXCLUIDOS = Map.of();
}
