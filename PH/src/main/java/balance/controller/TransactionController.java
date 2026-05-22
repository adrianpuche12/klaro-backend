package balance.controller;

import balance.model.Transaction;
import balance.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    @Autowired
    private TransactionService transactionService;

//    @GetMapping
//    public ResponseEntity<List<Transaction>> getAll() {
//        return ResponseEntity.ok(transactionService.findAll());
//    }
//
//    @GetMapping("/{id}")
//    public ResponseEntity<Transaction> getById(@PathVariable Long id) {
//        return transactionService.findById(id)
//                .map(ResponseEntity::ok)
//                .orElse(ResponseEntity.notFound().build());
//    }
//
//    @PostMapping
//    public ResponseEntity<Transaction> create(@RequestBody Transaction transaction) {
//        return ResponseEntity.ok(transactionService.save(transaction));
//    }
//
//    @PutMapping("/{id}")
//    public ResponseEntity<Transaction> update(@PathVariable Long id, @RequestBody Transaction updated) {
//        return transactionService.findById(id)
//                .map(existing -> {
//                    updated.setId(id);
//                    return ResponseEntity.ok(transactionService.save(updated));
//                })
//                .orElse(ResponseEntity.notFound().build());
//    }
//
//    @DeleteMapping("/{id}")
//    public ResponseEntity<Void> delete(@PathVariable Long id) {
//        transactionService.deleteById(id);
//        return ResponseEntity.noContent().build();
//    }

    //PRUEBA ACTUALIZACION SERVIDOR
    //OTRO COMENTARIO

    @GetMapping("/store/{storeId}")
    public ResponseEntity<List<Transaction>> getByStoreId(@PathVariable Long storeId) {
        return ResponseEntity.ok(transactionService.findByStoreId(storeId));
    }

    @GetMapping("/date-range-store")
    public ResponseEntity<List<Transaction>> getByDateRangeAndStore(
        @RequestParam LocalDate startDate,
        @RequestParam LocalDate endDate,
        @RequestParam Long storeId) {
        return ResponseEntity.ok(transactionService.findByDateBetweenAndStoreId(startDate, endDate, storeId));
    }
}
