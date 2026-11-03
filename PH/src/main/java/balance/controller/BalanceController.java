package balance.controller;

import balance.model.Store;
import balance.model.Transaction;
import balance.service.BalanceService;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.StoreScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;


/**
 * "Finanzas" (transacciones). Etapa 1 de permisos (P2): además del módulo, cada usuario
 * lee, crea, edita y borra solo transacciones de sus locales permitidos; el balance se
 * calcula sobre esos locales. Root y las cuentas sin perfil acotado ven toda la empresa.
 */
@RestController
@RequestMapping("/transactions")
public class BalanceController {

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private PermissionGuard permissionGuard;

    /** "Finanzas" es una sola pantalla que cubre estos 3 módulos (ver Sidebar.tsx) --
     * alcanza con tener cualquiera de los tres marcado en el Role. */
    private void assertFinanceAccess() {
        permissionGuard.assertAccessAny(
                PermissionModule.TRANSACTIONS, PermissionModule.SALARY_PAYMENTS, PermissionModule.SUPPLIER_PAYMENTS);
    }

    private static Long storeIdOf(Store store) {
        return store != null ? store.getId() : null;
    }

    // Método para agregar una nueva transacción
    @PostMapping
    public ResponseEntity<?> addTransaction(@Valid @RequestBody Transaction transaction, BindingResult result) {
        assertFinanceAccess();
        permissionGuard.assertStoreAllowed(storeIdOf(transaction.getStore()));
        // Verificar si hay errores de validación
        if (result.hasErrors()) {
            // Construir una respuesta con los errores
            StringBuilder errorMessages = new StringBuilder("Errores de validación:");
            result.getFieldErrors().forEach(error ->
                    errorMessages.append(" ").append(error.getField()).append(": ").append(error.getDefaultMessage()).append(".")
            );
            return ResponseEntity.status(400).body(errorMessages.toString());
        }

        try {
            Transaction savedTransaction = balanceService.saveTransaction(transaction);

            // Devolver respuesta con código de estado 201 (Created)
            return ResponseEntity.status(201).body(savedTransaction);
        } catch (Exception e) {
            // Manejo genérico de errores
            return ResponseEntity.status(500).body("Error al registrar la transacción: " + e.getMessage());
        }
    }


    // Método para obtener el balance entre dos fechas (de los locales permitidos)
    @GetMapping("/balance")
    public ResponseEntity<BigDecimal> getBalance(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        assertFinanceAccess();
        BigDecimal balance = balanceService.calculateBalance(startDate, endDate, permissionGuard.storeScope());
        return ResponseEntity.ok(balance);
    }

    // Método para obtener todas las transacciones (de los locales permitidos)
    @GetMapping
    public ResponseEntity<List<Transaction>> getAllTransactions() {
        assertFinanceAccess();
        StoreScope scope = permissionGuard.storeScope();
        List<Transaction> transactions = balanceService.getAllTransactions();
        if (!scope.unrestricted()) {
            transactions = transactions.stream().filter(t -> scope.allows(t.getStore())).toList();
        }
        return ResponseEntity.ok(transactions);
    }

    // Método para actualizar una transacción por ID
    @PutMapping("/{id}")
    public ResponseEntity<Transaction> updateTransaction(
            @PathVariable Long id,
            @RequestBody Transaction transaction) {
        assertFinanceAccess();
        Optional<Transaction> existingTransaction = balanceService.getTransactionById(id);
        if (existingTransaction.isPresent()) {
            Transaction updatedTransaction = existingTransaction.get();
            permissionGuard.assertStoreAllowed(storeIdOf(updatedTransaction.getStore()));
            if (transaction.getStore() != null) {
                // Moverla de local: el destino también tiene que ser uno de los suyos.
                permissionGuard.assertStoreAllowed(storeIdOf(transaction.getStore()));
            }
            updatedTransaction.setType(transaction.getType());
            updatedTransaction.setAmount(transaction.getAmount());
            updatedTransaction.setDate(transaction.getDate());
            updatedTransaction.setDescription(transaction.getDescription());
            if (transaction.getStore() != null) {
                updatedTransaction.setStore(transaction.getStore());
            }

            // Guardar la transacción actualizada
            Transaction savedTransaction = balanceService.saveTransaction(updatedTransaction);
            return ResponseEntity.ok(savedTransaction);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // Método para eliminar una transacción por ID
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable Long id) {
        assertFinanceAccess();
        Optional<Transaction> existing = balanceService.getTransactionById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        permissionGuard.assertStoreAllowed(storeIdOf(existing.get().getStore()));
        if (balanceService.deleteTransaction(id)) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
