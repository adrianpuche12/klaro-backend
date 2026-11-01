package balance.sales.service;

import balance.common.enums.ShiftStatus;
import balance.model.Store;
import balance.sales.model.Sale;
import balance.sales.model.Shift;
import balance.sales.repository.SaleRepository;
import balance.sales.repository.ShiftRepository;
import balance.tenant.context.TenantContext;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.ShiftReadScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Permisos por rol sobre turnos y ventas del POS (prioridad #1). */
@ExtendWith(MockitoExtension.class)
class ShiftAccessServiceTest {

    private static final Long TENANT = 1L;
    private static final Long STORE = 10L;

    @InjectMocks private ShiftAccessService shiftAccess;
    @Mock private ShiftRepository shiftRepository;
    @Mock private SaleRepository saleRepository;
    @Mock private PermissionGuard permissionGuard;

    @BeforeEach void setTenant() { TenantContext.setTenantId(TENANT); }
    @AfterEach  void clear()     { TenantContext.clear(); }

    private Shift shift(Long id, String owner, ShiftStatus status) {
        Store store = new Store();
        store.setId(STORE);
        Shift s = new Shift();
        s.setId(id);
        s.setTenantId(TENANT);
        s.setStore(store);
        s.setUsername(owner);
        s.setStatus(status);
        lenient().when(shiftRepository.findByIdAndTenantId(id, TENANT)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void cashier_cannotRead_closedShiftOfAnotherUser() {
        shift(5L, "otra.cajera", ShiftStatus.CLOSED);
        when(permissionGuard.shiftReadScope(STORE)).thenReturn(ShiftReadScope.OWN);
        when(permissionGuard.currentUsername()).thenReturn("test.cajero");

        assertThatThrownBy(() -> shiftAccess.assertCanRead(5L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cashier_canRead_ownClosedShift_caseInsensitive() {
        shift(5L, "Test.Cajero", ShiftStatus.CLOSED);
        when(permissionGuard.shiftReadScope(STORE)).thenReturn(ShiftReadScope.OWN);
        when(permissionGuard.currentUsername()).thenReturn("test.cajero");

        assertThatCode(() -> shiftAccess.assertCanRead(5L)).doesNotThrowAnyException();
    }

    @Test
    void cashier_canRead_openShiftOfTheStore_evenIfOpenedByAnother() {
        // El cajero vende en el turno abierto del local aunque lo haya abierto otro.
        shift(5L, "encargado", ShiftStatus.OPEN);
        when(permissionGuard.shiftReadScope(STORE)).thenReturn(ShiftReadScope.OWN);

        assertThatCode(() -> shiftAccess.assertCanRead(5L)).doesNotThrowAnyException();
        verify(permissionGuard, never()).currentUsername();
    }

    @Test
    void manager_canRead_anyShift() {
        shift(5L, "otra.cajera", ShiftStatus.CLOSED);
        when(permissionGuard.shiftReadScope(STORE)).thenReturn(ShiftReadScope.ALL);

        assertThatCode(() -> shiftAccess.assertCanRead(5L)).doesNotThrowAnyException();
    }

    @Test
    void readOfSale_usesTheSalesShift() {
        Shift s = shift(5L, "otra.cajera", ShiftStatus.CLOSED);
        Sale sale = new Sale();
        sale.setShift(s);
        when(saleRepository.findByIdAndTenantId(77L, TENANT)).thenReturn(Optional.of(sale));
        when(permissionGuard.shiftReadScope(STORE)).thenReturn(ShiftReadScope.OWN);
        when(permissionGuard.currentUsername()).thenReturn("test.cajero");

        assertThatThrownBy(() -> shiftAccess.assertCanReadSale(77L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void operate_requiresPosOnTheShiftsStore() {
        shift(5L, "test.cajero", ShiftStatus.OPEN);
        doThrow(new AccessDeniedException("No tenés acceso a este módulo"))
                .when(permissionGuard).assertAccess(PermissionModule.POS, STORE);

        assertThatThrownBy(() -> shiftAccess.assertCanOperate(5L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unknownShift_isLeftToTheService_withoutCheckingPermissions() {
        when(shiftRepository.findByIdAndTenantId(999L, TENANT)).thenReturn(Optional.empty());

        assertThatCode(() -> shiftAccess.assertCanRead(999L)).doesNotThrowAnyException();
        assertThatCode(() -> shiftAccess.assertCanOperate(999L)).doesNotThrowAnyException();
        verifyNoInteractions(permissionGuard);
    }
}
