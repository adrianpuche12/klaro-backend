package balance.sales.service;

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

    @Transactional
    public ShiftResponseDTO openShift(Long storeId, String username) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        if (shiftRepository.existsByStoreIdAndStatusAndTenantId(storeId, "OPEN", tenantId)) {
            throw new IllegalStateException("Ya existe un turno abierto para este local");
        }

        Shift shift = new Shift();
        shift.setStore(store);
        shift.setUsername(username);
        shift.setStatus("OPEN");
        shift.setCode(generateCode(store));
        shift.setTenantId(tenantId);
        shiftRepository.save(shift);
        return ShiftResponseDTO.from(shift);
    }

    @Transactional
    public ShiftResponseDTO closeShift(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Shift shift = shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Turno no encontrado"));
        if ("CLOSED".equals(shift.getStatus())) {
            throw new IllegalStateException("El turno ya está cerrado");
        }
        shift.setStatus("CLOSED");
        shift.setClosedAt(LocalDateTime.now());
        shiftRepository.save(shift);
        return ShiftResponseDTO.from(shift);
    }

    public ShiftResponseDTO getActiveShift(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return shiftRepository.findByStoreIdAndStatusAndTenantId(storeId, "OPEN", tenantId)
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

    public ShiftResponseDTO getById(Long shiftId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .map(ShiftResponseDTO::from)
                .orElseThrow(() -> new IllegalArgumentException("Turno no encontrado"));
    }

    private String generateCode(Store store) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmm"));
        String storePart = store.getName()
                .replaceAll("[^a-zA-Z]", "")
                .toUpperCase();
        if (storePart.length() > 3) storePart = storePart.substring(0, 3);
        return "T-" + date + "-" + time + "-" + storePart;
    }
}
