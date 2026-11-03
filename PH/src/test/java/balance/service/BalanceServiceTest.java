package balance.service;

import balance.model.Store;
import balance.model.Transaction;
import balance.repository.StoreRepository;
import balance.repository.TransactionRepository;
import balance.tenant.context.TenantContext;
import balance.users.service.PermissionGuard.StoreScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Una transacción nunca puede quedar asociada al local de otra empresa. */
@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    private static final Long TENANT_ID = 1L;

    @InjectMocks private BalanceService balanceService;
    @Mock private TransactionRepository transactionRepository;
    @Mock private StoreRepository storeRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    private Transaction txWithStore(Long storeId) {
        Transaction t = new Transaction();
        Store ref = new Store();
        ref.setId(storeId);
        t.setStore(ref);
        return t;
    }

    @Test
    void save_rejectsStoreOfAnotherCompany() {
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> balanceService.saveTransaction(txWithStore(99L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void save_resolvesOwnStore_andSetsTenant() {
        Store own = new Store();
        own.setId(1L);
        own.setTenantId(TENANT_ID);
        own.setName("Centro");
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(own));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction saved = balanceService.saveTransaction(txWithStore(1L));

        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getStore().getName()).isEqualTo("Centro");
    }

    @Test
    void save_withoutStore_isAllowed() {
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> balanceService.saveTransaction(new Transaction())).doesNotThrowAnyException();
        verifyNoInteractions(storeRepository);
    }

    @Test
    void calculateBalance_restrictedUser_onlyCountsHisStores() {
        // P2: el balance de Finanzas no puede incluir los montos de locales ajenos.
        Store mine = new Store(); mine.setId(1L);
        Store other = new Store(); other.setId(2L);
        Transaction income = new Transaction();
        income.setType("income"); income.setAmount(new java.math.BigDecimal("100")); income.setStore(mine);
        Transaction foreignIncome = new Transaction();
        foreignIncome.setType("income"); foreignIncome.setAmount(new java.math.BigDecimal("900")); foreignIncome.setStore(other);
        when(transactionRepository.findByTenantIdAndDateRange(eq(TENANT_ID), any(), any()))
                .thenReturn(java.util.List.of(income, foreignIncome));

        var start = java.time.LocalDate.of(2026, 11, 1);
        assertThat(balanceService.calculateBalance(start, start, StoreScope.only(java.util.List.of(1L))))
                .isEqualByComparingTo("100");
        assertThat(balanceService.calculateBalance(start, start, StoreScope.all()))
                .isEqualByComparingTo("1000");
    }
}
