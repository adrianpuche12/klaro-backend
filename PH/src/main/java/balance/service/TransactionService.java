package balance.service;

import balance.model.Transaction;
import balance.repository.StoreRepository;
import balance.repository.TransactionRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionService {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StoreRepository storeRepository;

    public List<Transaction> findByStoreId(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return transactionRepository.findByStoreIdAndTenantIdOrderByDateDesc(storeId, tenantId);
    }
}
