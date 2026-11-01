package balance.service;

import balance.model.Transaction;
import balance.repository.StoreRepository;
import balance.repository.TransactionRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class BalanceService {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StoreRepository storeRepository;

    public Transaction saveTransaction(Transaction transaction) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        transaction.setTenantId(tenantId);
        // El local tiene que ser de esta empresa (las FK no pasan por RLS: sin esto se podía
        // asociar una transacción al local de otra empresa, en el alta y en la edición).
        if (transaction.getStore() != null && transaction.getStore().getId() != null) {
            transaction.setStore(TenantSecurityUtils.requireStore(
                    transaction.getStore().getId(), tenantId, storeRepository));
        }
        return transactionRepository.save(transaction);
    }

    public List<Transaction> getAllTransactions() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return transactionRepository.findByTenantIdOrderByDateDesc(tenantId);
    }

    public BigDecimal calculateBalance(LocalDate startDate, LocalDate endDate) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        LocalDate adjustedEndDate = endDate.plusDays(1);
        List<Transaction> transactions = transactionRepository
                .findByTenantIdAndDateRange(tenantId, startDate, adjustedEndDate);

        if (transactions == null || transactions.isEmpty()) return BigDecimal.ZERO;

        BigDecimal income = transactions.stream()
                .filter(t -> "income".equalsIgnoreCase(t.getType()))
                .map(t -> new BigDecimal(t.getAmount().toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expense = transactions.stream()
                .filter(t -> "expense".equalsIgnoreCase(t.getType()))
                .map(t -> new BigDecimal(t.getAmount().toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return income.subtract(expense);
    }

    public Optional<Transaction> getTransactionById(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return transactionRepository.findByIdAndTenantId(id, tenantId);
    }

    public boolean deleteTransaction(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return transactionRepository.findByIdAndTenantId(id, tenantId)
                .map(t -> {
                    transactionRepository.delete(t);
                    return true;
                }).orElse(false);
    }
}
