package balance.service;

import balance.model.SalaryPayment;
import balance.repository.SalaryPaymentRepository;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SalaryPaymentService {

    @Autowired private SalaryPaymentRepository salaryPaymentRepository;
    @Autowired private StoreRepository storeRepository;

    public List<SalaryPayment> findAll() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return salaryPaymentRepository.findByTenantIdOrderBySalaryDateDesc(tenantId);
    }

    public Optional<SalaryPayment> findById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return salaryPaymentRepository.findByIdAndTenantId(id, tenantId);
    }

    public SalaryPayment save(SalaryPayment salaryPayment) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        salaryPayment.setTenantId(tenantId);
        return salaryPaymentRepository.save(salaryPayment);
    }

    public void deleteById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        salaryPaymentRepository.findByIdAndTenantId(id, tenantId)
                .ifPresent(s -> salaryPaymentRepository.deleteById(id));
    }

    public List<SalaryPayment> findByStoreId(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return salaryPaymentRepository.findByStoreIdAndTenantIdOrderBySalaryDateDesc(storeId, tenantId);
    }
}
