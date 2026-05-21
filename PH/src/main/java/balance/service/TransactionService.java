package balance.service;

import balance.model.Transaction;
import balance.repository.StoreRepository;
import balance.repository.TransactionRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class TransactionService {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StoreRepository storeRepository;

    public List<Transaction> findAll() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return transactionRepository.findByTenantIdOrderByDateDesc(tenantId);
    }

    public Optional<Transaction> findById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return transactionRepository.findByIdAndTenantId(id, tenantId);
    }

    public Transaction save(Transaction transaction) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        transaction.setTenantId(tenantId);
        return transactionRepository.save(transaction);
    }

    public void deleteById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        transactionRepository.findByIdAndTenantId(id, tenantId)
                .ifPresent(t -> transactionRepository.deleteById(id));
    }

    public List<Transaction> findByStoreId(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return transactionRepository.findByStoreIdAndTenantIdOrderByDateDesc(storeId, tenantId);
    }

    public List<Transaction> findByDateBetweenAndStoreId(LocalDate startDate, LocalDate endDate, Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return transactionRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, startDate, endDate);
    }
}
