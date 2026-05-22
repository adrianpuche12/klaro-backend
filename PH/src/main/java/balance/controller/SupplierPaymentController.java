package balance.controller;

import balance.model.SupplierPayment;
import balance.service.SupplierPaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/supplier-payments")
public class SupplierPaymentController {

    @Autowired
    private SupplierPaymentService supplierPaymentService;

//    @GetMapping
//    public ResponseEntity<List<SupplierPayment>> getAll() {
//        return ResponseEntity.ok(supplierPaymentService.findAll());
//    }
//
//    @GetMapping("/{id}")
//    public ResponseEntity<SupplierPayment> getById(@PathVariable Long id) {
//        return supplierPaymentService.findById(id)
//                .map(ResponseEntity::ok)
//                .orElse(ResponseEntity.notFound().build());
//    }
//
//    @PostMapping
//    public ResponseEntity<SupplierPayment> create(@RequestBody SupplierPayment supplierPayment) {
//        return ResponseEntity.ok(supplierPaymentService.save(supplierPayment));
//    }
//
//    @PutMapping("/{id}")
//    public ResponseEntity<SupplierPayment> update(@PathVariable Long id, @RequestBody SupplierPayment updated) {
//        return supplierPaymentService.findById(id)
//                .map(existing -> {
//                    updated.setId(id);
//                    return ResponseEntity.ok(supplierPaymentService.save(updated));
//                })
//                .orElse(ResponseEntity.notFound().build());
//    }
//
//    @DeleteMapping("/{id}")
//    public ResponseEntity<Void> delete(@PathVariable Long id) {
//        supplierPaymentService.deleteById(id);
//        return ResponseEntity.noContent().build();
//    }

    // 🆕 Endpoint para obtener pagos por store
    @GetMapping("/store/{storeId}")
    public ResponseEntity<List<SupplierPayment>> getByStoreId(@PathVariable Long storeId) {
        return ResponseEntity.ok(supplierPaymentService.findByStoreId(storeId));
    }
}
