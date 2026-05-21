package balance.sales.service;

import balance.common.enums.ShiftStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.sales.dto.ShiftResponseDTO;
import balance.sales.model.Shift;
import balance.sales.repository.ShiftRepository;
import balance.tenant.context.TenantContext;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShiftServiceTest {

    private static final Long TENANT_ID = 1L;

    @InjectMocks private ShiftService shiftService;

    @Mock private ShiftRepository shiftRepository;
    @Mock private StoreRepository storeRepository;

    @BeforeEach
    void setTenantContext() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Store buildStore(Long id, String name) {
        Store s = new Store();
        s.setId(id);
        s.setName(name);
        s.setTenantId(TENANT_ID);
        return s;
    }

    private Shift buildShift(Long id, Store store, ShiftStatus status) {
        Shift shift = new Shift();
        shift.setStore(store);
        shift.setStatus(status);
        shift.setCode("T-20260514-0900-DAN");
        shift.setUsername("cajero01");
        shift.setTenantId(TENANT_ID);
        return shift;
    }

    // ── openShift — formato del código ────────────────────────────────────────

    @Test
    void openShift_codeMatchesExpectedPattern() {
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(shiftRepository.existsByStoreIdAndStatusAndTenantId(1L, ShiftStatus.OPEN, TENANT_ID)).thenReturn(false);
        when(shiftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShiftResponseDTO result = shiftService.openShift(1L, "cajero01");

        assertThat(result.getCode()).matches("T-\\d{8}-\\d{4}-[A-Z]{1,3}");
    }

    @Test
    void openShift_codeEndsWithFirstThreeLettersOfStoreName() {
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(shiftRepository.existsByStoreIdAndStatusAndTenantId(1L, ShiftStatus.OPEN, TENANT_ID)).thenReturn(false);
        when(shiftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShiftResponseDTO result = shiftService.openShift(1L, "cajero01");

        assertThat(result.getCode()).endsWith("-DAN");
    }

    @Test
    void openShift_codeStripsNonLettersFromStoreName() {
        when(storeRepository.findByIdAndTenantId(2L, TENANT_ID)).thenReturn(Optional.of(buildStore(2L, "El Paraiso")));
        when(shiftRepository.existsByStoreIdAndStatusAndTenantId(2L, ShiftStatus.OPEN, TENANT_ID)).thenReturn(false);
        when(shiftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShiftResponseDTO result = shiftService.openShift(2L, "cajero02");

        assertThat(result.getCode()).endsWith("-ELP");
    }

    @Test
    void openShift_codeContainsTodayDate() {
        String todayStr = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(shiftRepository.existsByStoreIdAndStatusAndTenantId(1L, ShiftStatus.OPEN, TENANT_ID)).thenReturn(false);
        when(shiftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShiftResponseDTO result = shiftService.openShift(1L, "cajero01");

        assertThat(result.getCode()).contains(todayStr);
    }

    @Test
    void openShift_setsStatusToOpen() {
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(shiftRepository.existsByStoreIdAndStatusAndTenantId(1L, ShiftStatus.OPEN, TENANT_ID)).thenReturn(false);
        when(shiftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShiftResponseDTO result = shiftService.openShift(1L, "cajero01");

        assertThat(result.getStatus()).isEqualTo("OPEN");
        assertThat(result.getUsername()).isEqualTo("cajero01");
    }

    @Test
    void openShift_throwsWhenStoreNotFound() {
        when(storeRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftService.openShift(99L, "cajero"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local no encontrado");
    }

    @Test
    void openShift_throwsWhenShiftAlreadyOpen() {
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(shiftRepository.existsByStoreIdAndStatusAndTenantId(1L, ShiftStatus.OPEN, TENANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> shiftService.openShift(1L, "cajero01"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ya existe un turno abierto");
    }

    // ── closeShift ────────────────────────────────────────────────────────────

    @Test
    void closeShift_setsStatusToClosedAndRegistersClosedAt() {
        Store store = buildStore(1L, "Danli");
        Shift shift = buildShift(1L, store, ShiftStatus.OPEN);

        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(shift));
        when(shiftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShiftResponseDTO result = shiftService.closeShift(1L);

        assertThat(result.getStatus()).isEqualTo("CLOSED");
        assertThat(shift.getClosedAt()).isNotNull();
    }

    @Test
    void closeShift_throwsWhenShiftNotFound() {
        when(shiftRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftService.closeShift(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Turno no encontrado");
    }

    @Test
    void closeShift_throwsWhenAlreadyClosed() {
        Store store = buildStore(1L, "Danli");
        Shift shift = buildShift(1L, store, ShiftStatus.CLOSED);

        when(shiftRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(shift));

        assertThatThrownBy(() -> shiftService.closeShift(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya está cerrado");
    }

    // ── getActiveShift ────────────────────────────────────────────────────────

    @Test
    void getActiveShift_returnsNullWhenNoActiveShift() {
        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(buildStore(1L, "Danli")));
        when(shiftRepository.findByStoreIdAndStatusAndTenantId(1L, ShiftStatus.OPEN, TENANT_ID)).thenReturn(Optional.empty());

        ShiftResponseDTO result = shiftService.getActiveShift(1L);

        assertThat(result).isNull();
    }

    @Test
    void getActiveShift_returnsShiftWhenExists() {
        Store store = buildStore(1L, "Danli");
        Shift shift = buildShift(1L, store, ShiftStatus.OPEN);

        when(storeRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(store));
        when(shiftRepository.findByStoreIdAndStatusAndTenantId(1L, ShiftStatus.OPEN, TENANT_ID)).thenReturn(Optional.of(shift));

        ShiftResponseDTO result = shiftService.getActiveShift(1L);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("OPEN");
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    void getById_throwsWhenNotFound() {
        when(shiftRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shiftService.getById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Turno no encontrado");
    }
}
