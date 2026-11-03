package balance.storage.service;

import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Quién puede subir y ver comprobantes.
 *
 * Los comprobantes se adjuntan desde el formulario de carga (DynamicFormScreen):
 * depósitos de cierre, pagos a proveedores y sueldos van a {@code /api/forms/**}
 * (módulo OPERATIONS) y las transacciones a {@code /transactions} (pantalla
 * "Finanzas": TRANSACTIONS, SALARY_PAYMENTS o SUPPLIER_PAYMENTS). Por eso alcanza
 * con tener cualquiera de esos módulos EN ESE LOCAL: es el mismo conjunto que ya
 * permite crear el registro al que se adjunta el archivo.
 */
@Service
public class ComprobanteAccessService {

    static final List<PermissionModule> MODULOS_COMPROBANTE = List.of(
            PermissionModule.OPERATIONS,
            PermissionModule.TRANSACTIONS,
            PermissionModule.SALARY_PAYMENTS,
            PermissionModule.SUPPLIER_PAYMENTS);

    /** Entidades que guardan un comprobante en {@code imageUri} y tienen local y tenant. */
    private static final List<String> ENTIDADES_CON_COMPROBANTE = List.of(
            "Transaction", "ClosingDeposit", "SupplierPayment", "SalaryPayment", "BankDeposit");

    @Autowired private PermissionGuard permissionGuard;
    @Autowired private StoreRepository storeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** Subir: el local es obligatorio, tiene que ser del tenant y el usuario tiene que tener acceso. */
    public void assertCanUpload(Long storeId) {
        if (storeId == null) {
            throw new AccessDeniedException("Debés especificar un local");
        }
        Long tenantId = TenantSecurityUtils.requireTenantId();
        // El guard ya valida el tenant del local para perfiles acotados; para usuarios
        // legacy no lo hace, así que se valida acá igual (un local de otra empresa nunca).
        storeRepository.findByIdAndTenantId(storeId, tenantId)
                .orElseThrow(() -> new AccessDeniedException("Acceso denegado"));
        assertAnyModuleOnStore(storeId);
    }

    /**
     * Ver: el comprobante tiene que estar adjunto a algún registro del tenant y el
     * usuario tiene que tener acceso al local de ese registro. Un archivo suelto
     * (subido pero nunca adjuntado) no se puede ver: 404.
     *
     * @param refs las formas en que pudo quedar guardado (clave nueva y URL pública vieja)
     */
    @Transactional(readOnly = true)
    public void assertCanView(Set<String> refs) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Set<Long> storeIds = storesOwning(refs, tenantId);
        if (storeIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comprobante no encontrado");
        }
        AccessDeniedException last = null;
        for (Long storeId : storeIds) {
            try {
                assertAnyModuleOnStore(storeId);
                return;
            } catch (AccessDeniedException e) {
                last = e;
            }
        }
        throw last;
    }

    private Set<Long> storesOwning(Set<String> refs, Long tenantId) {
        Set<Long> storeIds = new LinkedHashSet<>();
        List<String> values = new ArrayList<>(refs);
        for (String entity : ENTIDADES_CON_COMPROBANTE) {
            List<Long> ids = entityManager.createQuery(
                            "SELECT e.store.id FROM " + entity + " e "
                                    + "WHERE e.tenantId = :tenantId AND e.imageUri IN :refs", Long.class)
                    .setParameter("tenantId", tenantId)
                    .setParameter("refs", values)
                    .getResultList();
            storeIds.addAll(ids);
        }
        return storeIds;
    }

    private void assertAnyModuleOnStore(Long storeId) {
        AccessDeniedException last = null;
        for (PermissionModule module : MODULOS_COMPROBANTE) {
            try {
                permissionGuard.assertAccess(module, storeId);
                return;
            } catch (AccessDeniedException e) {
                last = e;
            }
        }
        throw last;
    }
}
