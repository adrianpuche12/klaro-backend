package balance.sales.service;

import balance.common.enums.ShiftStatus;
import balance.sales.model.Shift;
import balance.sales.repository.SaleRepository;
import balance.sales.repository.ShiftRepository;
import balance.tenant.context.TenantSecurityUtils;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import balance.users.service.PermissionGuard.ShiftReadScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Control de acceso a turnos y ventas del POS por rol (prioridad #1 del producto).
 *
 * Los endpoints de turnos/ventas reciben un shiftId o saleId, no un storeId: este servicio
 * resuelve el local del turno (siempre dentro del tenant actual) y delega en
 * {@link PermissionGuard}.
 * <ul>
 *   <li><b>Operar</b> (abrir/cerrar turno, vender, anular, egresos): módulo POS en ese local.</li>
 *   <li><b>Leer</b> (detalle, ventas, resumen, egresos): según {@link PermissionGuard#shiftReadScope}.
 *       Con alcance OWN solo se ven los turnos propios y el turno ABIERTO del local (el cajero
 *       vende en él aunque lo haya abierto otro).</li>
 * </ul>
 * Si el turno o la venta no existen en el tenant no se decide acá: se deja seguir para que el
 * servicio responda como siempre (404/400), sin filtrar si el id existe en otra empresa.
 */
@Service
public class ShiftAccessService {

    @Autowired private ShiftRepository shiftRepository;
    @Autowired private SaleRepository saleRepository;
    @Autowired private PermissionGuard permissionGuard;

    public void assertCanOperate(Long shiftId) {
        findShift(shiftId).ifPresent(s -> permissionGuard.assertAccess(PermissionModule.POS, s.getStore().getId()));
    }

    public void assertCanRead(Long shiftId) {
        findShift(shiftId).ifPresent(this::assertCanRead);
    }

    public void assertCanOperateSale(Long saleId) {
        findSaleShift(saleId).ifPresent(s -> permissionGuard.assertAccess(PermissionModule.POS, s.getStore().getId()));
    }

    public void assertCanReadSale(Long saleId) {
        findSaleShift(saleId).ifPresent(this::assertCanRead);
    }

    private void assertCanRead(Shift shift) {
        ShiftReadScope scope = permissionGuard.shiftReadScope(shift.getStore().getId());
        if (scope == ShiftReadScope.ALL || shift.getStatus() == ShiftStatus.OPEN) {
            return;
        }
        String owner = shift.getUsername();
        if (owner == null || !owner.equalsIgnoreCase(permissionGuard.currentUsername())) {
            throw new AccessDeniedException("No tenés acceso a este turno");
        }
    }

    private Optional<Shift> findShift(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return shiftRepository.findByIdAndTenantId(shiftId, tenantId);
    }

    private Optional<Shift> findSaleShift(Long saleId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return saleRepository.findByIdAndTenantId(saleId, tenantId).map(sale -> sale.getShift());
    }
}
