package balance.storage.service;

import balance.model.Store;
import balance.model.SupplierPayment;
import balance.repository.StoreRepository;
import balance.repository.SupplierPaymentRepository;
import balance.tenant.context.TenantContext;
import balance.users.model.PermissionModule;
import balance.users.service.PermissionGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * Las consultas JPQL de {@link ComprobanteAccessService} contra un Postgres real:
 * encuentran el registro dueño por la URL pública vieja o por la clave nueva, y
 * nunca un registro de otro tenant.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(ComprobanteAccessService.class)
class ComprobanteAccessQueryIT {

    private static final Long TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 2L;
    private static final String KEY = "test/acme/comprobantes/1_a.jpg";
    private static final String LEGACY_URL = "https://test.r2.example.com/" + KEY;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ComprobanteAccessService service;
    @Autowired StoreRepository storeRepository;
    @Autowired SupplierPaymentRepository supplierPaymentRepository;
    @MockBean PermissionGuard permissionGuard;

    private Store store;

    @BeforeEach
    void setup() {
        supplierPaymentRepository.deleteAll();
        storeRepository.deleteAll();
        store = new Store();
        store.setName("Local Test");
        store.setTenantId(TENANT_ID);
        store = storeRepository.save(store);
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void savePayment(String imageUri, Long tenantId) {
        SupplierPayment p = new SupplierPayment();
        p.setTenantId(tenantId);
        p.setSupplier("Proveedor");
        p.setAmount(new BigDecimal("10.00"));
        p.setPaymentDate(LocalDate.now());
        p.setUsername("cajero");
        p.setStore(store);
        p.setImageUri(imageUri);
        supplierPaymentRepository.save(p);
    }

    @Test
    void findsOwnerRecord_savedWithLegacyPublicUrl() {
        savePayment(LEGACY_URL, TENANT_ID);

        assertThatCode(() -> service.assertCanView(Set.of(KEY, LEGACY_URL))).doesNotThrowAnyException();
        verify(permissionGuard).assertAccess(PermissionModule.OPERATIONS, store.getId());
    }

    @Test
    void findsOwnerRecord_savedWithNewKey() {
        savePayment(KEY, TENANT_ID);

        assertThatCode(() -> service.assertCanView(Set.of(KEY, LEGACY_URL))).doesNotThrowAnyException();
    }

    @Test
    void recordOfAnotherTenant_isNotFound() {
        savePayment(KEY, OTHER_TENANT_ID);

        assertThatThrownBy(() -> service.assertCanView(Set.of(KEY, LEGACY_URL)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
