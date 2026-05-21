package balance.service;

import balance.model.SupplierPayment;
import balance.repository.StoreRepository;
import balance.repository.SupplierPaymentRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SupplierPaymentService {

    @Autowired private SupplierPaymentRepository supplierPaymentRepository;
    @Autowired private StoreRepository storeRepository;

    public List<SupplierPayment> findAll() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return supplierPaymentRepository.findByTenantIdOrderByPaymentDateDesc(tenantId);
    }

    public Optional<SupplierPayment> findById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return supplierPaymentRepository.findByIdAndTenantId(id, tenantId);
    }

    public SupplierPayment save(SupplierPayment supplierPayment) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        supplierPayment.setTenantId(tenantId);
        return supplierPaymentRepository.save(supplierPayment);
    }

    public void deleteById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        supplierPaymentRepository.findByIdAndTenantId(id, tenantId)
                .ifPresent(s -> supplierPaymentRepository.deleteById(id));
    }

    public List<SupplierPayment> findByStoreId(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return supplierPaymentRepository.findByStoreIdAndTenantIdOrderByPaymentDateDesc(storeId, tenantId);
    }
}
