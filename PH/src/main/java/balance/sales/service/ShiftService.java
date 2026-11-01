package balance.sales.service;

import balance.tenant.service.TenantConfigService;
import balance.common.enums.ShiftStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.sales.dto.ShiftResponseDTO;
import balance.sales.model.Shift;
import balance.sales.repository.ShiftRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
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

        if (shiftRepository.existsByStoreIdAndStatusAndTenantId(storeId, ShiftStatus.OPEN, tenantId)) {
            throw new IllegalStateException("Ya existe un turno abierto para este local");
        }

        Shift shift = new Shift();
        shift.setStore(store);
        shift.setUsername(username);
        shift.setStatus(ShiftStatus.OPEN);
        shift.setCode(generateCode(store));
        shift.setTenantId(tenantId);
        shiftRepository.save(shift);
        return ShiftResponseDTO.from(shift);
    }

    @Transactional
    public ShiftResponseDTO closeShift(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = shiftRepository.findForUpdate(shiftId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Turno no encontrado"));
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
                .orElseThrow(() -> new IllegalArgumentException("Turno no encontrado"));
    }

    private String generateCode(Store store) {
        String date = tenantConfigService.today().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // Hora de la empresa (no la del servidor, que corre en UTC), igual que la fecha.
        String time = LocalTime.now(tenantConfigService.zoneId()).format(DateTimeFormatter.ofPattern("HHmm"));
        String storePart = store.getName()
                .replaceAll("[^a-zA-Z]", "")
                .toUpperCase();
        if (storePart.length() > 3) storePart = storePart.substring(0, 3);
        return "T-" + date + "-" + time + "-" + storePart;
    }
}
