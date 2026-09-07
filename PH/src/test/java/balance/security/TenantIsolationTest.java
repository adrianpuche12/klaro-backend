package balance.security;

import balance.catalog.service.StoreV2Service;
import balance.model.Store;
import balance.repository.ClosingDepositRepository;
import balance.repository.SalaryPaymentRepository;
import balance.repository.StoreRepository;
import balance.repository.SupplierPaymentRepository;
import balance.repository.TransactionRepository;
import balance.catalog.repository.CategoryRepository;
import balance.catalog.repository.ProductRepository;
import balance.inventory.repository.InventoryMovementRepository;
import balance.inventory.repository.InventoryStockRepository;
import balance.sales.repository.SaleRepository;
import balance.sales.service.SalesService;
import balance.sales.repository.ShiftRepository;
import balance.service.FormsService;
import balance.tax.repository.TaxRepository;
import balance.tax.service.TaxService;
import balance.tenant.context.TenantContext;
import balance.tenant.context.TenantSecurityUtils;
import balance.users.model.AppUser;
import balance.users.repository.AppUserRepository;
import balance.users.service.AppUserService;
import balance.users.service.KeycloakAdminService;
import balance.users.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifica que ningún servicio exponga datos de un tenant a otro.
 * La aislación se implementa vía TenantContext (ThreadLocal) propagado
 * desde el JWT a través de TenantFilter → TenantSecurityUtils.requireTenantId().
 *
 * Cada test verifica que los repositorios siempre se consultan con el tenantId
 * del contexto activo, nunca con el de otro tenant.
 */
@ExtendWith(MockitoExtension.class)
class TenantIsolationTest {

    private static final Long TENANT_A = 1L;
    private static final Long TENANT_B = 2L;

    // ── Mocks para StoreV2Service ──────────────────────────────────────────────
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

    // ── Mocks para AppUserService (SPRINT-09) ───────────────────────────────────
    @InjectMocks private AppUserService appUserService;
    @Mock private AppUserRepository     appUserRepository;
    @Mock private KeycloakAdminService  keycloakAdminService;
    @Mock private RoleService           roleService;

    @AfterEach void clearContext() { TenantContext.clear(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id, Long tenantId) {
        Store s = new Store();
        s.setId(id);
        s.setName("Local " + id);
        s.setActive(true);
        s.setTenantId(tenantId);
        return s;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Sin contexto de tenant
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void requireTenantId_throwsSecurityException_whenNoContextSet() {
        TenantContext.clear(); // asegurar que no hay contexto

        assertThatThrownBy(TenantSecurityUtils::requireTenantId)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("No tenant context");
    }

    @Test
    void storeService_throwsSecurityException_whenNoTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> storeV2Service.findAll())
                .isInstanceOf(SecurityException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // StoreV2Service — aislamiento por tenant
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findAll_queriesOnlyCurrentTenantStores() {
        TenantContext.setTenantId(TENANT_A);
        Store storeA = buildStore(1L, TENANT_A);
        when(storeRepository.findByTenantId(TENANT_A)).thenReturn(List.of(storeA));

        storeV2Service.findAll();

        verify(storeRepository).findByTenantId(TENANT_A);
        verify(storeRepository, never()).findByTenantId(TENANT_B);
    }

    @Test
    void findAll_tenantBCannotSeetenantAStores() {
        // Tenant B está en contexto — solo ve sus propios stores
        TenantContext.setTenantId(TENANT_B);
        when(storeRepository.findByTenantId(TENANT_B)).thenReturn(List.of()); // B no tiene stores

        var result = storeV2Service.findAll();

        assertThat(result).isEmpty();
        // Nunca consulta los stores de A
        verify(storeRepository, never()).findByTenantId(TENANT_A);
    }

    @Test
    void findById_tenantBCannotAccessTenantAStore() {
        TenantContext.setTenantId(TENANT_B);
        // El store 1 pertenece a tenant A, así que findByIdAndTenantId(1, B) devuelve vacío
        when(storeRepository.findByIdAndTenantId(1L, TENANT_B)).thenReturn(Optional.empty());

        var result = storeV2Service.findById(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void findById_tenantACanAccessItsOwnStore() {
        TenantContext.setTenantId(TENANT_A);
        Store storeA = buildStore(1L, TENANT_A);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_A)).thenReturn(Optional.of(storeA));

        var result = storeV2Service.findById(1L);

        assertThat(result).isPresent();
    }

    @Test
    void requireStore_blocksAccessToOtherTenantStore() {
        // TenantSecurityUtils.requireStore verifica que el storeId pertenezca al tenant activo
        TenantContext.setTenantId(TENANT_A);
        // Store 99 pertenece a tenant B → no existe bajo tenant A
        when(storeRepository.findByIdAndTenantId(99L, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                TenantSecurityUtils.requireStore(99L, TENANT_A, storeRepository))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local no encontrado");
    }

    @Test
    void create_alwaysSetsContextTenantId() {
        TenantContext.setTenantId(TENANT_A);
        Store saved = buildStore(10L, TENANT_A);
        when(storeRepository.save(any())).thenReturn(saved);

        balance.catalog.dto.StoreRequestDTO dto = new balance.catalog.dto.StoreRequestDTO();
        dto.setName("Nuevo Local");
        storeV2Service.create(dto);

        var captor = org.mockito.ArgumentCaptor.forClass(Store.class);
        verify(storeRepository).save(captor.capture());
        // El store guardado SIEMPRE lleva el tenantId del contexto activo
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_A);
        assertThat(captor.getValue().getTenantId()).isNotEqualTo(TENANT_B);
    }

    @Test
    void delete_tenantBCannotDeleteTenantAStore() {
        TenantContext.setTenantId(TENANT_B);
        // Store 1 pertenece a A → no existe bajo B
        when(storeRepository.existsByIdAndTenantId(1L, TENANT_B)).thenReturn(false);

        boolean result = storeV2Service.delete(1L);

        assertThat(result).isFalse();
        verify(storeRepository, never()).deleteById(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cambio de contexto — misma JVM, distintos tenants en secuencia
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void contextSwitch_tenantAFollowedByTenantB_noDataLeakage() {
        // Simula dos requests consecutivos en la misma JVM (como un pool de hilos HTTP)
        // Request 1: Tenant A
        TenantContext.setTenantId(TENANT_A);
        Store storeA = buildStore(1L, TENANT_A);
        when(storeRepository.findByTenantId(TENANT_A)).thenReturn(List.of(storeA));
        var resultA = storeV2Service.findAll();
        assertThat(resultA).hasSize(1);
        TenantContext.clear();

        // Request 2: Tenant B
        TenantContext.setTenantId(TENANT_B);
        when(storeRepository.findByTenantId(TENANT_B)).thenReturn(List.of());
        var resultB = storeV2Service.findAll();
        assertThat(resultB).isEmpty();
        TenantContext.clear();

        // Verificar que cada request consultó solo su propio tenant
        verify(storeRepository).findByTenantId(TENANT_A);
        verify(storeRepository).findByTenantId(TENANT_B);
    }

    @Test
    void toggle_tenantBCannotToggleTenantAStore() {
        TenantContext.setTenantId(TENANT_B);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_B)).thenReturn(Optional.empty());

        var result = storeV2Service.toggle(1L);

        assertThat(result).isEmpty();
        verify(storeRepository, never()).save(any());
    }

    @Test
    void update_tenantBCannotUpdateTenantAStore() {
        TenantContext.setTenantId(TENANT_B);
        when(storeRepository.findByIdAndTenantId(1L, TENANT_B)).thenReturn(Optional.empty());

        balance.catalog.dto.StoreRequestDTO dto = new balance.catalog.dto.StoreRequestDTO();
        dto.setName("Hackear Store A");
        var result = storeV2Service.update(1L, dto);

        assertThat(result).isEmpty();
        verify(storeRepository, never()).save(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AppUserService.updateStoreAccess — SPRINT-09
    // Un admin del tenant A no puede asignarle a un usuario del tenant A
    // un local del tenant B. Este es exactamente el hueco de seguridad que
    // PH v2 nunca tuvo que cerrar (single-tenant) y que Belopia sí necesita.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void updateStoreAccess_adminCannotAssignForeignTenantStore_toOwnUser() {
        TenantContext.setTenantId(TENANT_A);
        AppUser ownUser = new AppUser();
        ownUser.setId(5L);
        ownUser.setTenantId(TENANT_A);
        when(appUserRepository.findByIdAndTenantId(5L, TENANT_A)).thenReturn(Optional.of(ownUser));
        // El store 99 pertenece a TENANT_B -> no existe bajo TENANT_A
        when(storeRepository.findByIdAndTenantId(99L, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appUserService.updateStoreAccess(5L, List.of(99L)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(appUserRepository, never()).save(any());
    }
}
