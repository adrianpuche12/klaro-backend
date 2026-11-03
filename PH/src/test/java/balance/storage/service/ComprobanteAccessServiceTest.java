package balance.storage.service;

import balance.model.Store;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantContext;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Quién puede subir y ver comprobantes: módulo + local + tenant. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComprobanteAccessServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Set<String> REFS = Set.of(
            "dev/acme/comprobantes/1_a.jpg", "https://pub-test.r2.dev/dev/acme/comprobantes/1_a.jpg");

    @InjectMocks private ComprobanteAccessService service;
    @Mock private PermissionGuard permissionGuard;
    @Mock private StoreRepository storeRepository;
    @Mock private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ── upload ──────────────────────────────────────────────────────────────

    @Test
    void upload_withoutStore_is403() {
        assertThatThrownBy(() -> service.assertCanUpload(null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("local");
        verifyNoInteractions(permissionGuard);
    }

    @Test
    void upload_storeOfAnotherTenant_is403_evenForLegacyUser() {
        // PermissionGuard (mock no-op) = usuario legacy, que el guard deja pasar sin mirar el local.
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertCanUpload(99L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void upload_withoutAnyComprobanteModule_is403() {
        when(storeRepository.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(new Store()));
        doThrow(new AccessDeniedException("No tenés acceso a este módulo"))
                .when(permissionGuard).assertAccess(any(PermissionModule.class), eq(5L));

        assertThatThrownBy(() -> service.assertCanUpload(5L)).isInstanceOf(AccessDeniedException.class);
        verify(permissionGuard, times(ComprobanteAccessService.MODULOS_COMPROBANTE.size()))
                .assertAccess(any(PermissionModule.class), eq(5L));
    }

    @Test
    void upload_withOperationsOnStore_isAllowed() {
        when(storeRepository.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(new Store()));

        assertThatCode(() -> service.assertCanUpload(5L)).doesNotThrowAnyException();
        verify(permissionGuard).assertAccess(PermissionModule.OPERATIONS, 5L);
    }

    @Test
    void upload_withOnlyFinanceModule_isAllowed() {
        // Las transacciones se cargan desde "Finanzas" (TRANSACTIONS/SALARY/SUPPLIER), sin OPERATIONS.
        when(storeRepository.findByIdAndTenantId(5L, TENANT_ID)).thenReturn(Optional.of(new Store()));
        doThrow(new AccessDeniedException("No tenés acceso a este módulo"))
                .when(permissionGuard).assertAccess(PermissionModule.OPERATIONS, 5L);

        assertThatCode(() -> service.assertCanUpload(5L)).doesNotThrowAnyException();
        verify(permissionGuard).assertAccess(PermissionModule.TRANSACTIONS, 5L);
    }

    // ── view ────────────────────────────────────────────────────────────────

    @Test
    void view_fileNotAttachedToAnyRecordOfTenant_is404() {
        stubOwningStores(List.of());

        assertThatThrownBy(() -> service.assertCanView(REFS))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void view_recordOfStoreWithoutAccess_is403() {
        stubOwningStores(List.of(7L));
        doThrow(new AccessDeniedException("No tenés acceso a este local"))
                .when(permissionGuard).assertAccess(any(PermissionModule.class), eq(7L));

        assertThatThrownBy(() -> service.assertCanView(REFS)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void view_recordOfAllowedStore_isAllowed_andQueriesFilterByTenant() {
        TypedQuery<Long> query = stubOwningStores(List.of(7L));

        assertThatCode(() -> service.assertCanView(REFS)).doesNotThrowAnyException();
        verify(query, atLeastOnce()).setParameter("tenantId", TENANT_ID);
        verify(permissionGuard).assertAccess(PermissionModule.OPERATIONS, 7L);
    }

    @SuppressWarnings("unchecked")
    private TypedQuery<Long> stubOwningStores(List<Long> storeIds) {
        TypedQuery<Long> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(storeIds);
        return query;
    }
}
