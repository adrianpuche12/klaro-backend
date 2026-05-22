package balance.controller;

import balance.model.Transaction;
import balance.service.BalanceService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;


@RestController
@RequestMapping("/transactions")
public class BalanceController {

    @Autowired
    private BalanceService balanceService;

    // Método para agregar una nueva transacción
    @PostMapping
    public ResponseEntity<?> addTransaction(@Valid @RequestBody Transaction transaction, BindingResult result) {
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


    // Método para obtener el balance entre dos fechas
    @GetMapping("/balance")
    public ResponseEntity<BigDecimal> getBalance(
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        BigDecimal balance = balanceService.calculateBalance(startDate, endDate);
        return ResponseEntity.ok(balance);
    }

    // Método para obtener todas las transacciones
    @GetMapping
    public ResponseEntity<List<Transaction>> getAllTransactions() {
        List<Transaction> transactions = balanceService.getAllTransactions();
        return ResponseEntity.ok(transactions);
    }

    // Método para actualizar una transacción por ID
    @PutMapping("/{id}")
    public ResponseEntity<Transaction> updateTransaction(
            @PathVariable Long id,
            @RequestBody Transaction transaction) {
        Optional<Transaction> existingTransaction = balanceService.getTransactionById(id);
        if (existingTransaction.isPresent()) {
            Transaction updatedTransaction = existingTransaction.get();
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
        if (balanceService.deleteTransaction(id)) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
