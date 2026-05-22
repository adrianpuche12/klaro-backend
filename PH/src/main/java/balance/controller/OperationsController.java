package balance.controller;

import balance.dto.AllOperationsDTO;
import balance.model.ClosingDeposit;
import balance.model.SupplierPayment;
import balance.model.SalaryPayment;
import balance.model.Store;
import balance.service.FormsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

// ================================================================
// AGREGAR ESTE IMPORT AL INICIO DEL ARCHIVO
// ================================================================
import balance.model.GastoAdmin;


@RestController
@RequestMapping("/api/operations")
public class OperationsController {

    @Autowired
    private FormsService formsService;

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

        // Paginación en memoria
        int from = page * size;
        if (from >= result.size()) return ResponseEntity.ok(List.of());
        int to   = Math.min(from + size, result.size());
        return ResponseEntity.ok(result.subList(from, to));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<AllOperationsDTO>> getMyOperations(
            @RequestParam String username,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        List<AllOperationsDTO> result = formsService.getOperationsByUsername(username);
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
        switch (type.toUpperCase()) {

            // ================================================================
// MODIFICAR EL MÉTODO updateOperation() - AGREGAR ESTE CASO AL SWITCH
// ================================================================

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
        switch (type.toUpperCase()) {
            // ================================================================
// MODIFICAR EL MÉTODO deleteOperation() - AGREGAR ESTE CASO AL SWITCH
// ================================================================

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
