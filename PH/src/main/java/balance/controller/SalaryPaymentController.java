package balance.controller;

import balance.model.SalaryPayment;
import balance.service.SalaryPaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/salary-payments")
public class SalaryPaymentController {

    @Autowired
    private SalaryPaymentService salaryPaymentService;

//    @GetMapping
//    public ResponseEntity<List<SalaryPayment>> getAll() {
//        return ResponseEntity.ok(salaryPaymentService.findAll());
//    }
//
//    @GetMapping("/{id}")
//    public ResponseEntity<SalaryPayment> getById(@PathVariable Long id) {
//        return salaryPaymentService.findById(id)
//                .map(ResponseEntity::ok)
//                .orElse(ResponseEntity.notFound().build());
//    }
//
//    @PostMapping
//    public ResponseEntity<SalaryPayment> create(@RequestBody SalaryPayment salaryPayment) {
//        return ResponseEntity.ok(salaryPaymentService.save(salaryPayment));
//    }
//
//    @PutMapping("/{id}")
//    public ResponseEntity<SalaryPayment> update(@PathVariable Long id, @RequestBody SalaryPayment updated) {
//        return salaryPaymentService.findById(id)
//                .map(existing -> {
//                    updated.setId(id);
//                    return ResponseEntity.ok(salaryPaymentService.save(updated));
//                })
//                .orElse(ResponseEntity.notFound().build());
//    }
//
//    @DeleteMapping("/{id}")
//    public ResponseEntity<Void> delete(@PathVariable Long id) {
//        salaryPaymentService.deleteById(id);
//        return ResponseEntity.noContent().build();
//    }

    // 🆕 Endpoint para filtrar por Store ID
    @GetMapping("/store/{storeId}")
    public ResponseEntity<List<SalaryPayment>> getByStoreId(@PathVariable Long storeId) {
        return ResponseEntity.ok(salaryPaymentService.findByStoreId(storeId));
    }
}
