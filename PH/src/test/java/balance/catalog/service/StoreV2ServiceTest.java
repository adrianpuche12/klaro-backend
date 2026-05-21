package balance.catalog.service;

import balance.catalog.dto.StoreRequestDTO;
import balance.catalog.dto.StoreResponseDTO;
import balance.catalog.repository.CategoryRepository;
import balance.catalog.repository.ProductRepository;
import balance.inventory.repository.InventoryMovementRepository;
import balance.inventory.repository.InventoryStockRepository;
import balance.model.Store;
import balance.repository.ClosingDepositRepository;
import balance.repository.SalaryPaymentRepository;
import balance.repository.StoreRepository;
import balance.repository.SupplierPaymentRepository;
import balance.repository.TransactionRepository;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoreV2ServiceTest {

    private static final Long TENANT_ID = 1L;

    @InjectMocks private StoreV2Service storeV2Service;

    @Mock private StoreRepository             storeRepository;
    @Mock private ProductRepository           productRepository;
    @Mock private CategoryRepository          categoryRepository;
    @Mock private InventoryStockRepository    inventoryStockRepository;
    @Mock private InventoryMovementRepository inventoryMovementRepository;
    @Mock private TransactionRepository       transactionRepository;
    @Mock private ClosingDepositRepository    closingDepositRepository;
    @Mock private SupplierPaymentRepository   supplierPaymentRepository;
    @Mock private SalaryPaymentRepository     salaryPaymentRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id, String name) {
        Store s = new Store();
        s.setId(id);
        s.setName(name);
        s.setActive(true);
        s.setTenantId(TENANT_ID);
        return s;
    }

    private StoreRequestDTO buildRequest(String name) {
        StoreRequestDTO dto = new StoreRequestDTO();
        dto.setName(name);
        dto.setAddress("Calle Principal");
        dto.setPhone("+504 9999-0000");
        return dto;
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    void findAll_returnsOnlyTenantStores() {
        when(storeRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(buildStore(1L, "Local Central")));

        List<StoreResponseDTO> result = storeV2Service.findAll();

        assertThat(result).hasSize(1);
        verify(storeRepository).findByTenantId(TENANT_ID);
    }

    @Test
    void findAll_doesNotLeakOtherTenantData() {
        when(storeRepository.findByTenantId(TENANT_ID)).thenReturn(List.of());

        assertThat(storeV2Service.findAll()).isEmpty();
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    void findById_returnsStore_whenBelongsToTenant() {
        Store s = buildStore(1L, "Danli");
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(s));

        assertThat(storeV2Service.findById(1L)).isPresent();
    }

    @Test
    void findById_returnsEmpty_whenStoreIsFromOtherTenant() {
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThat(storeV2Service.findById(99L)).isEmpty();
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_setsTenantIdFromContext() {
        Store saved = buildStore(1L, "Nuevo Local");
        when(storeRepository.save(any())).thenReturn(saved);

        storeV2Service.create(buildRequest("Nuevo Local"));

        ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void create_setsActiveTrue() {
        Store saved = buildStore(1L, "Nuevo Local");
        when(storeRepository.save(any())).thenReturn(saved);

        storeV2Service.create(buildRequest("Nuevo Local"));

        ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository).save(captor.capture());
        assertThat(captor.getValue().getActive()).isTrue();
    }

    @Test
    void create_trimesName() {
        Store saved = buildStore(1L, "Local Central");
        when(storeRepository.save(any())).thenReturn(saved);

        storeV2Service.create(buildRequest("  Local Central  "));

        ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Local Central");
    }

    // ── toggle ────────────────────────────────────────────────────────────────

    @Test
    void toggle_deactivatesActiveStore() {
        Store s = buildStore(1L, "Local"); s.setActive(true);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(s));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        storeV2Service.toggle(1L);

        assertThat(s.getActive()).isFalse();
    }

    @Test
    void toggle_activatesInactiveStore() {
        Store s = buildStore(1L, "Local"); s.setActive(false);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(s));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        storeV2Service.toggle(1L);

        assertThat(s.getActive()).isTrue();
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_throwsWhenStoreHasHistory() {
        when(storeRepository.existsByIdAndTenantId(1L, TENANT_ID)).thenReturn(true);
        when(transactionRepository.findByStoreIdAndTenantIdOrderByDateDesc(eq(1L), eq(TENANT_ID)))
                .thenReturn(List.of(mock(balance.model.Transaction.class)));

        assertThatThrownBy(() -> storeV2Service.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("historial");

        verify(storeRepository, never()).deleteById(any());
    }

    @Test
    void delete_returnsFalse_whenStoreNotFound() {
        when(storeRepository.existsByIdAndTenantId(99L, TENANT_ID)).thenReturn(false);

        assertThat(storeV2Service.delete(99L)).isFalse();
        verify(storeRepository, never()).deleteById(any());
    }

    @Test
    void delete_deletesStoreAndProducts_whenNoHistory() {
        when(storeRepository.existsByIdAndTenantId(1L, TENANT_ID)).thenReturn(true);
        when(transactionRepository.findByStoreIdAndTenantIdOrderByDateDesc(eq(1L), eq(TENANT_ID))).thenReturn(List.of());
        when(closingDepositRepository.findByStoreIdAndTenantIdOrderByDepositDateDesc(eq(1L), eq(TENANT_ID))).thenReturn(List.of());
        when(supplierPaymentRepository.findByStoreIdAndTenantIdOrderByPaymentDateDesc(eq(1L), eq(TENANT_ID))).thenReturn(List.of());
        when(salaryPaymentRepository.findByStoreIdAndTenantIdOrderBySalaryDateDesc(eq(1L), eq(TENANT_ID))).thenReturn(List.of());
        when(productRepository.findByStoreIdOrderByNameAsc(1L)).thenReturn(List.of());
        when(categoryRepository.findRootsByStoreId(1L)).thenReturn(List.of());

        boolean result = storeV2Service.delete(1L);

        assertThat(result).isTrue();
        verify(storeRepository).deleteById(1L);
    }
}
