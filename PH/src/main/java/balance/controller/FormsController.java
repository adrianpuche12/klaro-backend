package balance.controller;

import balance.model.*;
import balance.service.FormsService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.StoreScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import balance.dto.GastoAdminRequestDTO;
import balance.dto.GastoAdminResponseDTO;
import balance.model.GastoAdmin;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Endpoints para la gestión de depósitos de cierres, pagos a proveedores y salarios.
 * Todos ellos se envían desde la misma pantalla "Operaciones" (DynamicFormScreen,
 * ver Sidebar.tsx) -- gateados por el módulo OPERATIONS, único que controla el
 * acceso a esa pantalla en el frontend.
 *
 * <p>Etapa 1 de permisos:
 * <ul>
 *   <li>P2: los listados se filtran por los locales permitidos del usuario y el alta valida
 *       que el local sea uno de los suyos.</li>
 *   <li>P4: el cajero (Role sin "gestionar usuarios") solo puede cargar "Depósito de Cierres",
 *       igual que la pantalla (CASHIER_FORM_TYPES en DynamicFormScreen.tsx, que decide por
 *       canManageUsers, no por nombre de usuario). Pagos a proveedores, salarios y gastos
 *       administrativos son movimientos de gestión: exigen {@link PermissionGuard#assertManage}
 *       en cada local afectado.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/forms")
public class FormsController {

    @Autowired
    private FormsService formsService;

    @Autowired
    private PermissionGuard permissionGuard;

    private void assertOperationsAccess() {
        permissionGuard.assertAccess(PermissionModule.OPERATIONS);
    }

    private static Long storeIdOf(Store store) {
        return store != null ? store.getId() : null;
    }

    /** "Todos" = todos los locales permitidos del usuario; un registro sin local no lo ve un usuario acotado. */
    private <T> List<T> onlyAllowedStores(List<T> rows, Function<T, Store> storeOf) {
        StoreScope scope = permissionGuard.storeScope();
        return scope.unrestricted() ? rows : rows.stream().filter(r -> scope.allows(storeOf.apply(r))).toList();
    }

    /** Un gasto administrativo se ve solo si el usuario tiene acceso a TODOS los locales entre los que se repartió. */
    private List<GastoAdmin> onlyAllowedGastos(List<GastoAdmin> gastos) {
        StoreScope scope = permissionGuard.storeScope();
        if (scope.unrestricted()) {
            return gastos;
        }
        return gastos.stream().filter(g -> {
            Set<Long> stores = formsService.storeIdsOfGastoAdmin(g.getId());
            return !stores.isEmpty() && stores.stream().allMatch(scope::allows);
        }).toList();
    }

    /** Escritura de gestión en uno o varios locales: encargado en cada uno. */
    private void assertCanManageStores(Collection<Long> storeIds) {
        if (storeIds.isEmpty()) {
            permissionGuard.assertManage(PermissionModule.OPERATIONS, null);
        }
        storeIds.forEach(storeId -> permissionGuard.assertManage(PermissionModule.OPERATIONS, storeId));
    }

    private static Set<Long> distributionStores(GastoAdminRequestDTO request) {
        Set<Long> ids = new HashSet<>();
        if (request.getDistribuciones() != null) {
            request.getDistribuciones().stream()
                    .map(GastoAdminRequestDTO.StoreDistribucion::getStoreId)
                    .filter(Objects::nonNull)
                    .forEach(ids::add);
        }
        return ids;
    }

    /**
     * Crea un nuevo depósito de cierre. Lo puede cargar el cajero (con OPERATIONS) en uno de
     * sus locales: es el único tipo que la pantalla le habilita.
     *
     * @param deposit Objeto ClosingDeposit con los datos del depósito a crear
     * @return ResponseEntity con el depósito creado y su ID asignado
     *
     * Ejemplo de JSON requerido:
     * {
     *     "closingsCount": 5,
     *     "amount": 1000.00,
     *     "periodStart": "2024-01-01",
     *     "periodEnd": "2024-01-31",
     *     "username": "usuario123"
     * }
     */
    @PostMapping("/closing-deposits")
    public ResponseEntity<ClosingDeposit> addClosingDeposit(
            @Valid @RequestBody ClosingDeposit deposit) {
        // Sin local (null) solo pasa quien no tiene locales acotados.
        permissionGuard.assertAccess(PermissionModule.OPERATIONS, storeIdOf(deposit.getStore()));
        return ResponseEntity.ok(formsService.createClosingDeposit(deposit));
    }

    /**
     * Obtiene todos los depósitos de cierre (de los locales permitidos).
     *
     * @return ResponseEntity con la lista de todos los depósitos
     */
    @GetMapping("/closing-deposits/all")
    public ResponseEntity<List<ClosingDeposit>> getAllClosingDeposits() {
        assertOperationsAccess();
        return ResponseEntity.ok(onlyAllowedStores(formsService.getAllClosingDeposits(), ClosingDeposit::getStore));
    }

    @PostMapping("/gasto-admin")
    public ResponseEntity<GastoAdminResponseDTO> addGastoAdmin(
            @Valid @RequestBody GastoAdminRequestDTO request) {
        assertOperationsAccess();
        assertCanManageStores(distributionStores(request));
        return ResponseEntity.ok(formsService.saveGastoAdmin(request));
    }

    @PutMapping("/gasto-admin/{id}")
    public ResponseEntity<GastoAdminResponseDTO> updateGastoAdmin(
            @PathVariable Long id,
            @Valid @RequestBody GastoAdminRequestDTO request) {
        assertOperationsAccess();
        // Se recrean las transacciones: hay que poder gestionar los locales de antes y los nuevos.
        Set<Long> stores = new HashSet<>(formsService.storeIdsOfGastoAdmin(id));
        stores.addAll(distributionStores(request));
        assertCanManageStores(stores);
        return ResponseEntity.ok(formsService.updateGastoAdminV2(id, request));
    }

    /**
     * Obtiene todos los gastos administrativos registrados.
     *
     * @return ResponseEntity con la lista de todos los gastos administrativos
     */
    @GetMapping("/gasto-admin/all")
    public ResponseEntity<List<GastoAdmin>> getAllGastosAdmin() {
        assertOperationsAccess();
        return ResponseEntity.ok(onlyAllowedGastos(formsService.getAllGastosAdmin()));
    }

    /**
     * Obtiene gastos administrativos en un rango de fechas.
     *
     * @param startDate Fecha inicial del rango (formato: YYYY-MM-DD)
     * @param endDate Fecha final del rango (formato: YYYY-MM-DD)
     * @return ResponseEntity con la lista de gastos administrativos encontrados
     */
    @GetMapping("/gasto-admin")
    public ResponseEntity<List<GastoAdmin>> getGastosAdmin(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        assertOperationsAccess();
        return ResponseEntity.ok(onlyAllowedGastos(formsService.getGastosAdmin(startDate, endDate)));
    }


    @GetMapping("/closing-deposits")
    public ResponseEntity<List<ClosingDeposit>> getClosingDeposits(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        assertOperationsAccess();
        return ResponseEntity.ok(onlyAllowedStores(
                formsService.getClosingDeposits(startDate, endDate), ClosingDeposit::getStore));
    }

    @GetMapping("/closing-deposits/store/{storeId}")
    public ResponseEntity<List<ClosingDeposit>> getByStoreId(@PathVariable Long storeId) {
        permissionGuard.assertAccess(PermissionModule.OPERATIONS, storeId);
        return ResponseEntity.ok(formsService.findByStoreId(storeId));
    }

    /**
     * Registra un nuevo pago a proveedor (movimiento de gestión: encargado en el local).
     *
     * @param payment Objeto SupplierPayment con los datos del pago a registrar
     * @return ResponseEntity con el pago registrado y su ID asignado
     *
     * Ejemplo de JSON requerido:
     * {
     *     "supplier": "Pollo Rey",
     *     "amount": 5000.00,
     *     "username": "usuario123",
     *     "description": "Pago quincenal de materia prima"
     * }
     */
    @PostMapping("/supplier-payments")
    public ResponseEntity<SupplierPayment> addSupplierPayment(
            @Valid @RequestBody SupplierPayment payment) {
        assertOperationsAccess();
        permissionGuard.assertManage(PermissionModule.OPERATIONS, storeIdOf(payment.getStore()));
        return ResponseEntity.ok(formsService.saveSupplierPayment(payment));
    }

    /**
     * Obtiene todos los pagos a proveedores (de los locales permitidos).
     *
     * @return ResponseEntity con la lista de todos los pagos a proveedores
     */
    @GetMapping("/supplier-payments/all")
    public ResponseEntity<List<SupplierPayment>> getAllSupplierPayments() {
        assertOperationsAccess();
        return ResponseEntity.ok(onlyAllowedStores(formsService.getAllSupplierPayments(), SupplierPayment::getStore));
    }

    /**
     * Obtiene todos los pagos a proveedores en un rango de fechas (de los locales permitidos).
     *
     * @param startDate Fecha inicial del rango (formato: YYYY-MM-DD)
     * @param endDate Fecha final del rango (formato: YYYY-MM-DD)
     * @return ResponseEntity con la lista de pagos encontrados
     */
    @GetMapping("/supplier-payments")
    public ResponseEntity<List<SupplierPayment>> getSupplierPayments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        assertOperationsAccess();
        return ResponseEntity.ok(onlyAllowedStores(
                formsService.getSupplierPayments(startDate, endDate), SupplierPayment::getStore));
    }

    /**
     * Registra un nuevo pago de salario (movimiento de gestión: encargado en el local).
     *
     * @param payment Objeto SalaryPayment con los datos del pago de salario
     * @return ResponseEntity con el pago registrado y su ID asignado
     *
     * Ejemplo de JSON requerido:
     * {
     *     "description": "Pago quincenal",
     *     "amount": 3000.00,
     *     "username": "usuario123"
     * }
     */
    @PostMapping("/salary-payments")
    public ResponseEntity<SalaryPayment> addSalaryPayment(
            @Valid @RequestBody SalaryPayment payment) {
        assertOperationsAccess();
        permissionGuard.assertManage(PermissionModule.OPERATIONS, storeIdOf(payment.getStore()));
        return ResponseEntity.ok(formsService.saveSalaryPayment(payment));
    }

     /**
     * Obtiene todos los pagos de salarios registrados (de los locales permitidos).
     *
     * @return ResponseEntity con la lista de todos los pagos de salarios
     */
    @GetMapping("/salary-payments")
    public ResponseEntity<List<SalaryPayment>> getAllSalaryPayments() {
        assertOperationsAccess();
        return ResponseEntity.ok(onlyAllowedStores(formsService.getAllSalaryPayments(), SalaryPayment::getStore));
    }
}
