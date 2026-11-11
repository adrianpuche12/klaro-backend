package balance.sales.service;

import balance.tenant.context.RecursoNoEncontradoException;
import balance.tenant.service.TenantConfigService;
import balance.common.enums.ShiftStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.sales.dto.ShiftResponseDTO;
import balance.sales.model.Shift;
import balance.sales.repository.ShiftRepository;
import balance.tenant.context.TenantSecurityUtils;
import balance.common.exception.ConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ShiftService {

    @Autowired private ShiftRepository shiftRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private TenantConfigService tenantConfigService;

    @Transactional
    public ShiftResponseDTO openShift(Long storeId, String username) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        // Dos aperturas simultáneas del mismo local se serializan acá (ver lockStoreForShiftOpening).
        shiftRepository.lockStoreForShiftOpening(storeId, tenantId);
        if (shiftRepository.existsByStoreIdAndStatusAndTenantId(storeId, ShiftStatus.OPEN, tenantId)) {
            throw new ConflictException(SHIFT_ALREADY_OPEN);
        }

        Shift shift = new Shift();
        shift.setStore(store);
        shift.setUsername(username);
        shift.setStatus(ShiftStatus.OPEN);
        shift.setCode(generateCode(store, tenantId));
        shift.setTenantId(tenantId);
        try {
            shiftRepository.saveAndFlush(shift);
        } catch (DataIntegrityViolationException e) {
            // Red de seguridad: el índice único parcial ux_shift_open (V14).
            if (violates(e, "ux_shift_open")) throw new ConflictException(SHIFT_ALREADY_OPEN);
            // Código repetido dentro de la empresa (V16). No debería pasar: lleva el id del local
            // y el correlativo del día, calculado con el local bloqueado. Mensaje claro, sin SQL.
            if (violates(e, "ux_shifts_tenant_code")) throw new ConflictException(SHIFT_CODE_TAKEN);
            throw e;
        }
        return ShiftResponseDTO.from(shift);
    }

    static final String SHIFT_ALREADY_OPEN = "Ya hay un turno abierto en este local";
    static final String SHIFT_CODE_TAKEN = "No se pudo abrir el turno en este momento. Probá de nuevo.";

    private static boolean violates(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && constraint.equals(cve.getConstraintName())) return true;
            if (t.getMessage() != null && t.getMessage().contains(constraint)) return true;
        }
        return false;
    }

    @Transactional
    public ShiftResponseDTO closeShift(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = shiftRepository.findForUpdate(shiftId, tenantId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Turno no encontrado"));
        if (ShiftStatus.CLOSED == shift.getStatus()) {
            throw new IllegalStateException("El turno ya está cerrado");
        }
        shift.setStatus(ShiftStatus.CLOSED);
        shift.setClosedAt(LocalDateTime.now());
        shiftRepository.save(shift);
        return ShiftResponseDTO.from(shift);
    }

    public ShiftResponseDTO getActiveShift(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return shiftRepository.findByStoreIdAndStatusAndTenantId(storeId, ShiftStatus.OPEN, tenantId)
                .map(ShiftResponseDTO::from)
                .orElse(null);
    }

    public List<ShiftResponseDTO> getShiftHistory(Long storeId, int page, int size) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return shiftRepository.findByStoreIdAndTenantIdOrderByOpenedAtDesc(
                storeId, tenantId, PageRequest.of(page, size))
                .stream().map(ShiftResponseDTO::from).toList();
    }

    /** Historial de un local solo con los turnos que abrió {@code username}. */
    public List<ShiftResponseDTO> getShiftHistoryByUser(Long storeId, String username, int page, int size) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return shiftRepository.findByStoreIdAndTenantIdAndUsernameIgnoreCaseOrderByOpenedAtDesc(
                storeId, tenantId, username, PageRequest.of(page, size))
                .stream().map(ShiftResponseDTO::from).toList();
    }

    public ShiftResponseDTO getById(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .map(ShiftResponseDTO::from)
                .orElseThrow(() -> new RecursoNoEncontradoException("Turno no encontrado"));
    }

    /**
     * {@code T-<fecha>-<HHmm>-<3 letras del local><id del local>-<n>}, con la fecha y la hora de la empresa.
     * n es el correlativo del día en ese local: se calcula con el local ya bloqueado
     * (lockStoreForShiftOpening), así que dos aperturas del mismo local no lo repiten, y el id del
     * local evita que dos locales de la empresa con el mismo prefijo choquen. Único por empresa
     * (V16: ux_shifts_tenant_code); entre empresas el código se puede repetir sin problema.
     * Ejemplo: T-20261111-0900-SUC12-2 (segundo turno del día del local 12, "Sucursal Centro").
     */
    String generateCode(Store store, Long tenantId) {
        String date = tenantConfigService.today().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // Hora de la empresa (no la del servidor, que corre en UTC), igual que la fecha.
        String time = LocalTime.now(tenantConfigService.zoneId()).format(DateTimeFormatter.ofPattern("HHmm"));
        String storePart = store.getName() == null ? "" : store.getName()
                .replaceAll("[^a-zA-Z]", "")
                .toUpperCase();
        if (storePart.length() > 3) storePart = storePart.substring(0, 3);
        long delDia = shiftRepository.countByStoreAndCodePrefix(store.getId(), tenantId, "T-" + date + "-%");
        return "T-" + date + "-" + time + "-" + storePart + store.getId() + "-" + (delDia + 1);
    }
}
