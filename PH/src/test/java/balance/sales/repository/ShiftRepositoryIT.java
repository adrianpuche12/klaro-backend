package balance.sales.repository;

import balance.common.enums.ShiftStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.sales.model.Shift;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(locations = "classpath:application-test.properties")
class ShiftRepositoryIT {

    private static final Long TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 2L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ShiftRepository shiftRepository;
    @Autowired StoreRepository storeRepository;

    private Store store;

    @BeforeEach
    void setup() {
        shiftRepository.deleteAll();
        storeRepository.deleteAll();

        store = new Store();
        store.setName("Danli Test");
        store.setTenantId(TENANT_ID);
        store = storeRepository.save(store);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Shift saveShift(ShiftStatus status, String code) {
        return saveShift(status, code, TENANT_ID);
    }

    private Shift saveShift(ShiftStatus status, String code, Long tenantId) {
        Shift shift = new Shift();
        shift.setStore(store);
        shift.setUsername("cajero01");
        shift.setStatus(status);
        shift.setCode(code);
        shift.setTenantId(tenantId);
        return shiftRepository.save(shift);
    }

    // ── existsByStoreIdAndStatusAndTenantId ──────────────────────────────────

    @Test
    void existsByStoreIdAndStatusAndTenantId_returnsTrueWhenOpenShiftExists() {
        saveShift(ShiftStatus.OPEN, "T-20260514-0900-DAN");

        boolean exists = shiftRepository.existsByStoreIdAndStatusAndTenantId(
                store.getId(), ShiftStatus.OPEN, TENANT_ID);

        assertThat(exists).isTrue();
    }

    @Test
    void existsByStoreIdAndStatusAndTenantId_returnsFalseWhenNoOpenShift() {
        saveShift(ShiftStatus.CLOSED, "T-20260514-0900-DAN");

        boolean exists = shiftRepository.existsByStoreIdAndStatusAndTenantId(
                store.getId(), ShiftStatus.OPEN, TENANT_ID);

        assertThat(exists).isFalse();
    }

    @Test
    void existsByStoreIdAndStatusAndTenantId_returnsFalseForDifferentTenant() {
        saveShift(ShiftStatus.OPEN, "T-20260514-0900-DAN", OTHER_TENANT_ID);

        boolean exists = shiftRepository.existsByStoreIdAndStatusAndTenantId(
                store.getId(), ShiftStatus.OPEN, TENANT_ID);

        assertThat(exists).isFalse();
    }

    // ── findByStoreIdAndStatusAndTenantId ─────────────────────────────────────

    @Test
    void findByStoreIdAndStatusAndTenantId_returnsOpenShift() {
        Shift saved = saveShift(ShiftStatus.OPEN, "T-20260514-0900-DAN");

        Optional<Shift> result = shiftRepository.findByStoreIdAndStatusAndTenantId(
                store.getId(), ShiftStatus.OPEN, TENANT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo("T-20260514-0900-DAN");
    }

    @Test
    void findByStoreIdAndStatusAndTenantId_returnsEmptyForDifferentTenant() {
        saveShift(ShiftStatus.OPEN, "T-20260514-0900-DAN", OTHER_TENANT_ID);

        Optional<Shift> result = shiftRepository.findByStoreIdAndStatusAndTenantId(
                store.getId(), ShiftStatus.OPEN, TENANT_ID);

        assertThat(result).isEmpty();
    }

    // ── findByStoreIdAndTenantIdOrderByOpenedAtDesc ──────────────────────────

    @Test
    void findByStoreIdAndTenantIdOrderByOpenedAtDesc_returnsMostRecentFirst() throws InterruptedException {
        saveShift(ShiftStatus.CLOSED, "T-20260514-0800-DAN");
        Thread.sleep(10);
        saveShift(ShiftStatus.CLOSED, "T-20260514-0900-DAN");
        Thread.sleep(10);
        saveShift(ShiftStatus.OPEN, "T-20260514-1000-DAN");

        List<Shift> result = shiftRepository.findByStoreIdAndTenantIdOrderByOpenedAtDesc(
                store.getId(), TENANT_ID);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getCode()).isEqualTo("T-20260514-1000-DAN");
        assertThat(result.get(2).getCode()).isEqualTo("T-20260514-0800-DAN");
    }

    @Test
    void findByStoreIdAndTenantIdOrderByOpenedAtDesc_returnsEmptyWhenNoShifts() {
        List<Shift> result = shiftRepository.findByStoreIdAndTenantIdOrderByOpenedAtDesc(
                store.getId(), TENANT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void findByStoreIdAndTenantIdOrderByOpenedAtDesc_excludesOtherTenants() {
        saveShift(ShiftStatus.OPEN, "T-20260514-0900-DAN", TENANT_ID);
        saveShift(ShiftStatus.OPEN, "T-20260514-0900-ELP", OTHER_TENANT_ID);

        List<Shift> result = shiftRepository.findByStoreIdAndTenantIdOrderByOpenedAtDesc(
                store.getId(), TENANT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCode()).isEqualTo("T-20260514-0900-DAN");
    }
}
