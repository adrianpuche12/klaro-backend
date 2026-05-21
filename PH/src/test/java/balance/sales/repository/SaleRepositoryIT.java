package balance.sales.repository;

import balance.common.enums.SaleStatus;
import balance.common.enums.ShiftStatus;
import balance.model.Store;
import balance.repository.StoreRepository;
import balance.sales.model.Sale;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(locations = "classpath:application-test.properties")
class SaleRepositoryIT {

    private static final Long TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 2L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired SaleRepository  saleRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired StoreRepository storeRepository;

    private Store store;
    private Shift shift;

    @BeforeEach
    void setup() {
        saleRepository.deleteAll();
        shiftRepository.deleteAll();
        storeRepository.deleteAll();

        store = new Store();
        store.setName("Danli Test");
        store.setTenantId(TENANT_ID);
        store = storeRepository.save(store);

        shift = new Shift();
        shift.setStore(store);
        shift.setUsername("cajero01");
        shift.setStatus(ShiftStatus.OPEN);
        shift.setCode("T-20260514-0900-DAN");
        shift.setTenantId(TENANT_ID);
        shift = shiftRepository.save(shift);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Sale saveSale(SaleStatus status, BigDecimal total) {
        return saveSale(status, total, TENANT_ID);
    }

    private Sale saveSale(SaleStatus status, BigDecimal total, Long tenantId) {
        Sale sale = new Sale();
        sale.setShift(shift);
        sale.setStore(store);
        sale.setUsername("cajero01");
        sale.setSaleDate(LocalDate.now());
        sale.setStatus(status);
        sale.setSubtotal(total);
        sale.setIsv(BigDecimal.ZERO);
        sale.setTotal(total);
        sale.setTenantId(tenantId);
        return saleRepository.save(sale);
    }

    // ── findOpenByShiftIdAndTenantId ──────────────────────────────────────────

    @Test
    void findOpenByShiftIdAndTenantId_returnsOnlyOpenSales() {
        saveSale(SaleStatus.OPEN,      new BigDecimal("200.00"));
        saveSale(SaleStatus.OPEN,      new BigDecimal("150.00"));
        saveSale(SaleStatus.CONFIRMED, new BigDecimal("100.00"));

        List<Sale> result = saleRepository.findOpenByShiftIdAndTenantId(shift.getId(), TENANT_ID);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(s -> SaleStatus.OPEN == s.getStatus());
    }

    @Test
    void findOpenByShiftIdAndTenantId_excludesOtherTenants() {
        saveSale(SaleStatus.OPEN, new BigDecimal("200.00"), TENANT_ID);
        saveSale(SaleStatus.OPEN, new BigDecimal("150.00"), OTHER_TENANT_ID);

        List<Sale> result = saleRepository.findOpenByShiftIdAndTenantId(shift.getId(), TENANT_ID);

        assertThat(result).hasSize(1);
    }

    @Test
    void findOpenByShiftIdAndTenantId_returnsEmptyWhenAllSalesConfirmed() {
        saveSale(SaleStatus.CONFIRMED, new BigDecimal("200.00"));

        List<Sale> result = saleRepository.findOpenByShiftIdAndTenantId(shift.getId(), TENANT_ID);

        assertThat(result).isEmpty();
    }

    // ── countOpenByShiftIdAndTenantId ─────────────────────────────────────────

    @Test
    void countOpenByShiftIdAndTenantId_returnsCorrectCount() {
        saveSale(SaleStatus.OPEN,      new BigDecimal("200.00"));
        saveSale(SaleStatus.OPEN,      new BigDecimal("150.00"));
        saveSale(SaleStatus.CONFIRMED, new BigDecimal("100.00"));

        long count = saleRepository.countOpenByShiftIdAndTenantId(shift.getId(), TENANT_ID);

        assertThat(count).isEqualTo(2);
    }

    // ── findByShiftIdAndTenantIdOrderByCreatedAtDesc ──────────────────────────

    @Test
    void findByShiftIdAndTenantIdOrderByCreatedAtDesc_returnsAllSalesOfTenant() {
        saveSale(SaleStatus.OPEN,      new BigDecimal("200.00"), TENANT_ID);
        saveSale(SaleStatus.CONFIRMED, new BigDecimal("150.00"), TENANT_ID);
        saveSale(SaleStatus.OPEN,      new BigDecimal("100.00"), OTHER_TENANT_ID);

        List<Sale> result = saleRepository.findByShiftIdAndTenantIdOrderByCreatedAtDesc(
                shift.getId(), TENANT_ID);

        assertThat(result).hasSize(2);
    }

    // ── findByShiftIdAndStatusAndTenantId ─────────────────────────────────────

    @Test
    void findByShiftIdAndStatusAndTenantId_filtersCorrectly() {
        saveSale(SaleStatus.OPEN,      new BigDecimal("200.00"));
        saveSale(SaleStatus.OPEN,      new BigDecimal("150.00"));
        saveSale(SaleStatus.CONFIRMED, new BigDecimal("100.00"));

        List<Sale> open = saleRepository.findByShiftIdAndStatusAndTenantId(
                shift.getId(), "OPEN", TENANT_ID);
        List<Sale> confirmed = saleRepository.findByShiftIdAndStatusAndTenantId(
                shift.getId(), "CONFIRMED", TENANT_ID);

        assertThat(open).hasSize(2);
        assertThat(confirmed).hasSize(1);
    }
}
