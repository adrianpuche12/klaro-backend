package balance.controller;

import balance.dto.AllOperationsDTO;
import balance.model.ClosingDeposit;
import balance.model.SupplierPayment;
import balance.model.SalaryPayment;
import balance.model.Store;
import balance.service.FormsService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.StoreScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import balance.model.GastoAdmin;


/** Vista agregada de depósitos/gastos/pagos, la misma familia de datos que
 * FormsController -- reachable solo desde la pantalla "Operaciones" (ver
 * Sidebar.tsx), gateada por OPERATIONS.
 *
 * Etapa 1 de permisos: los listados se filtran por los locales permitidos del usuario (P2),
 * "mine" queda atado al token (P3) y editar/borrar exige ser encargado en el local del
 * registro (P8, misma regla que el resto de las escrituras de gestión). */
@RestController
@RequestMapping("/api/operations")
public class OperationsController {

    @Autowired
    private FormsService formsService;

    @Autowired
    private PermissionGuard permissionGuard;

    private void assertOperationsAccess() {
        permissionGuard.assertAccess(PermissionModule.OPERATIONS);
    }

    private List<AllOperationsDTO> onlyAllowedStores(List<AllOperationsDTO> operations) {
        StoreScope scope = permissionGuard.storeScope();
        return scope.unrestricted()
                ? operations
                : operations.stream().filter(op -> scope.allows(op.getStoreId())).toList();
    }

    /**
     * Editar o borrar una operación: encargado (assertManage) en el local donde está hoy
     * el registro. Un gasto administrativo se reparte entre varios locales: hay que poder
     * gestionar todos.
     */
    private void assertCanManageOperation(String type, Long id) {
        if ("GASTO_ADMIN".equalsIgnoreCase(type)) {
            assertCanManageStores(formsService.storeIdsOfGastoAdmin(id));
        } else {
            permissionGuard.assertManage(PermissionModule.OPERATIONS, formsService.storeIdOf(type, id));
        }
    }

    private void assertCanManageStores(Collection<Long> storeIds) {
        if (storeIds.isEmpty()) {
            // Sin local: solo quien no tiene locales acotados (root, cuenta sin perfil).
            permissionGuard.assertManage(PermissionModule.OPERATIONS, null);
        }
        storeIds.forEach(storeId -> permissionGuard.assertManage(PermissionModule.OPERATIONS, storeId));
    }

    /**
     * Obtiene todas las operaciones del sistema.
     * Si se proporcionan fechas, filtra las operaciones dentro del rango especificado.
     * Si se proporciona storeId, filtra las operaciones por local.
     *
     * Ejemplos de uso en Postman:
     * GET http://localhost:8080/api/operations/all
     * GET http://localhost:8080/api/operations/all?startDate=2024-01-01&endDate=2024-01-31
     * GET http://localhost:8080/api/operations/all?storeId=1
     */
    @GetMapping("/all")
    public ResponseEntity<List<AllOperationsDTO>> getAllOperations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long storeId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        if (storeId != null) {
            permissionGuard.assertAccess(PermissionModule.OPERATIONS, storeId);
        } else {
            assertOperationsAccess();
        }

        List<AllOperationsDTO> result;

        if (startDate != null && endDate != null) {
            result = storeId != null
                    ? formsService.getOperationsByDateRangeAndStore(startDate, endDate, storeId)
                    : formsService.getOperationsByDateRange(startDate, endDate);
        } else if (storeId != null) {
            result = formsService.getOperationsByStore(storeId);
        } else {
            result = formsService.getAllOperations();
        }
        if (storeId == null) {
            result = onlyAllowedStores(result); // "todos los locales" = todos MIS locales
        }

        // Paginación en memoria
        int from = page * size;
        if (from >= result.size()) return ResponseEntity.ok(List.of());
        int to   = Math.min(from + size, result.size());
        return ResponseEntity.ok(result.subList(from, to));
    }

    /**
     * Historial de operaciones cargadas por un usuario. P3: atado al token — sin
     * {@code username} (o con el propio) devuelve las del usuario logueado; el de otro
     * usuario solo lo pide root o quien gestiona usuarios. Igual se filtra por locales.
     */
    @GetMapping("/mine")
    public ResponseEntity<List<AllOperationsDTO>> getMyOperations(
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        assertOperationsAccess();
        String me = permissionGuard.currentUsername();
        String target = (username == null || username.isBlank()) ? me : username.trim();
        if (!target.equalsIgnoreCase(me) && !permissionGuard.canManageUsers()) {
            throw new AccessDeniedException("Solo podés ver tus propias operaciones");
        }
        List<AllOperationsDTO> result = onlyAllowedStores(formsService.getOperationsByUsername(target));
        int from = page * size;
        if (from >= result.size()) return ResponseEntity.ok(List.of());
        int to   = Math.min(from + size, result.size());
        return ResponseEntity.ok(result.subList(from, to));
    }

    /**
     * Actualiza una operación existente.
     * La URL debe incluir el tipo (CLOSING, SUPPLIER o SALARY) y el id de la operación.
     *
     * Ejemplo en Postman para una operación CLOSING:
     * PUT http://localhost:8080/api/operations/CLOSING/1
     *
     * Cuerpo (JSON) para CLOSING:
     * {
     *   "amount": 1000.0,
     *   "username": "user1",
     *   "closingsCount": 2,
     *   "periodStart": "2024-01-01",
     *   "periodEnd": "2024-01-31",
     *   "store": { "id": 1 }
     * }
     *
     * Para SUPPLIER y SALARY se deben enviar los campos correspondientes (incluyendo "description" donde aplique).
     */
    @PutMapping("/{type}/{id}")
    public ResponseEntity<AllOperationsDTO> updateOperation(
            @PathVariable String type,
            @PathVariable Long id,
            @RequestBody AllOperationsDTO dto) {
        assertOperationsAccess();
        assertCanManageOperation(type, id);
        if (dto.getStoreId() != null && !"GASTO_ADMIN".equalsIgnoreCase(type)) {
            // Mover el registro a otro local: también hay que ser encargado en el destino.
            permissionGuard.assertManage(PermissionModule.OPERATIONS, dto.getStoreId());
        }
        switch (type.toUpperCase()) {
            case "GASTO_ADMIN":
                GastoAdmin gastoAdmin = new GastoAdmin();
                gastoAdmin.setMonto(dto.getAmount());
                gastoAdmin.setDescripcion(dto.getDescription());
                gastoAdmin.setUsername(dto.getUsername());
                if (dto.getDate() != null) {
                    gastoAdmin.setFecha(dto.getDate());
                }
                
                // Los porcentajes no se pueden extraer del DTO unificado
                // Se mantienen los valores originales
                GastoAdmin updatedGastoAdmin = formsService.updateGastoAdmin(id, gastoAdmin);
                return ResponseEntity.ok(AllOperationsDTO.fromGastoAdmin(updatedGastoAdmin));

            case "CLOSING":
                // Se actualiza sin descripción, ya que el modelo ClosingDeposit no la tiene.
                ClosingDeposit closingDeposit = new ClosingDeposit();
                closingDeposit.setAmount(dto.getAmount());
                closingDeposit.setUsername(dto.getUsername());
                closingDeposit.setClosingsCount(dto.getClosingsCount());
                closingDeposit.setPeriodStart(dto.getPeriodStart());
                closingDeposit.setPeriodEnd(dto.getPeriodEnd());
                if (dto.getDepositDate() != null) {
                    closingDeposit.setDepositDate(dto.getDepositDate());
                } else if (dto.getDate() != null) {
                    closingDeposit.setDepositDate(dto.getDate());
                }
                
                if (dto.getStoreId() != null) {
                    Store store = new Store();
                    store.setId(dto.getStoreId());
                    if (dto.getStoreName() != null) {
                        store.setName(dto.getStoreName());
                    }
                    closingDeposit.setStore(store);
                }
                
                ClosingDeposit updatedClosingDeposit = formsService.updateClosingDeposit(id, closingDeposit);
                return ResponseEntity.ok(AllOperationsDTO.fromClosingDeposit(updatedClosingDeposit));
                
            case "SUPPLIER":
                SupplierPayment supplierPayment = new SupplierPayment();
                supplierPayment.setAmount(dto.getAmount());
                supplierPayment.setDescription(dto.getDescription());
                supplierPayment.setUsername(dto.getUsername());
                supplierPayment.setSupplier(dto.getSupplier());
                if (dto.getPaymentDate() != null) {
                    supplierPayment.setPaymentDate(dto.getPaymentDate());
                } else if (dto.getDate() != null) {
                    supplierPayment.setPaymentDate(dto.getDate());
                }
                
                if (dto.getStoreId() != null) {
                    Store store = new Store();
                    store.setId(dto.getStoreId());
                    if (dto.getStoreName() != null) {
                        store.setName(dto.getStoreName());
                    }
                    supplierPayment.setStore(store);
                }
                
                SupplierPayment updatedSupplierPayment = formsService.updateSupplierPayment(id, supplierPayment);
                return ResponseEntity.ok(AllOperationsDTO.fromSupplierPayment(updatedSupplierPayment));
                
            case "SALARY":
                SalaryPayment salaryPayment = new SalaryPayment();
                salaryPayment.setAmount(dto.getAmount());
                salaryPayment.setDescription(dto.getDescription());
                salaryPayment.setUsername(dto.getUsername());               
                if (dto.getSalaryDate() != null) {
                    salaryPayment.setSalaryDate(dto.getSalaryDate());
                } else if (dto.getDate() != null) {
                    salaryPayment.setSalaryDate(dto.getDate());
                }
                
                if (dto.getStoreId() != null) {
                    Store store = new Store();
                    store.setId(dto.getStoreId());
                    if (dto.getStoreName() != null) {
                        store.setName(dto.getStoreName());
                    }
                    salaryPayment.setStore(store);
                }
                
                SalaryPayment updatedSalaryPayment = formsService.updateSalaryPayment(id, salaryPayment);
                return ResponseEntity.ok(AllOperationsDTO.fromSalaryPayment(updatedSalaryPayment));
                
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de operación inválido");
        }
    }

    /**
     * Elimina una operación existente.
     * La URL debe incluir el tipo (CLOSING, SUPPLIER o SALARY) y el id de la operación.
     *
     * Ejemplo en Postman:
     * DELETE http://localhost:8080/api/operations/SUPPLIER/3
     */
    @DeleteMapping("/{type}/{id}")
    public ResponseEntity<Void> deleteOperation(
            @PathVariable String type,
            @PathVariable Long id) {
        assertOperationsAccess();
        assertCanManageOperation(type, id);
        switch (type.toUpperCase()) {
            case "GASTO_ADMIN":
                formsService.deleteGastoAdmin(id);
                break;
            case "CLOSING":
                formsService.deleteClosingDeposit(id);
                break;
            case "SUPPLIER":
                formsService.deleteSupplierPayment(id);
                break;
            case "SALARY":
                formsService.deleteSalaryPayment(id);
                break;
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de operación inválido");
        }
        return ResponseEntity.noContent().build();
    }
}
