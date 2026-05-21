package balance.tax.service;

import balance.catalog.model.Category;
import balance.catalog.model.Product;
import balance.catalog.repository.ProductRepository;
import balance.tax.model.Tax;
import balance.tax.repository.TaxRepository;
import balance.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaxServiceTest {

    private static final Long TENANT_ID  = 1L;
    private static final Long PRODUCT_ID = 10L;
    private static final Long CATEGORY_ID = 20L;

    @InjectMocks private TaxService taxService;
    @Mock private TaxRepository    taxRepository;
    @Mock private ProductRepository productRepository;

    @BeforeEach void setTenant()   { TenantContext.setTenantId(TENANT_ID); }
    @AfterEach  void clearTenant() { TenantContext.clear(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Product buildProduct(Long id) {
        Product p = new Product();
        p.setId(id);
        p.setName("Pollo Entero");
        p.setPrice(new BigDecimal("200.00"));
        p.setActive(true);
        p.setTenantId(TENANT_ID);
        return p;
    }

    private Product buildProductWithCategory(Long id, Long categoryId) {
        Product p = buildProduct(id);
        Category cat = new Category();
        cat.setId(categoryId);
        p.setCategory(cat);
        return p;
    }

    private Tax buildTax(String type, String appliesTo, BigDecimal rate) {
        Tax t = new Tax();
        t.setTenantId(TENANT_ID);
        t.setName("ISV");
        t.setRate(rate);
        t.setType(type);
        t.setAppliesTo(appliesTo);
        t.setActive(true);
        return t;
    }

    // ── calculateTax — sin impuesto ───────────────────────────────────────────

    @Test
    void calculateTax_returnsZero_whenNoTaxesConfigured() {
        when(taxRepository.findActiveTaxesForProduct(TENANT_ID, PRODUCT_ID)).thenReturn(List.of());
        when(taxRepository.findActiveTaxesForAll(TENANT_ID)).thenReturn(List.of());

        BigDecimal result = taxService.calculateTax(TENANT_ID, buildProduct(PRODUCT_ID), new BigDecimal("100.00"));

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void calculateTax_returnsZero_whenAllTaxesInactive() {
        Tax inactiveTax = buildTax("PERCENTAGE", "ALL", new BigDecimal("15"));
        inactiveTax.setActive(false);

        when(taxRepository.findActiveTaxesForProduct(TENANT_ID, PRODUCT_ID)).thenReturn(List.of());
        when(taxRepository.findActiveTaxesForAll(TENANT_ID)).thenReturn(List.of()); // active=false not returned

        BigDecimal result = taxService.calculateTax(TENANT_ID, buildProduct(PRODUCT_ID), new BigDecimal("100.00"));

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── calculateTax — impuesto global (ALL) ──────────────────────────────────

    @Test
    void calculateTax_appliesGlobalPercentage() {
        Tax isv = buildTax("PERCENTAGE", "ALL", new BigDecimal("15"));
        when(taxRepository.findActiveTaxesForProduct(TENANT_ID, PRODUCT_ID)).thenReturn(List.of());
        when(taxRepository.findActiveTaxesForAll(TENANT_ID)).thenReturn(List.of(isv));

        BigDecimal result = taxService.calculateTax(TENANT_ID, buildProduct(PRODUCT_ID), new BigDecimal("200.00"));

        assertThat(result).isEqualByComparingTo("30.00"); // 200 * 15% = 30
    }

    @Test
    void calculateTax_appliesGlobalFixedAmount() {
        Tax fixed = buildTax("FIXED", "ALL", new BigDecimal("10.00"));
        when(taxRepository.findActiveTaxesForProduct(TENANT_ID, PRODUCT_ID)).thenReturn(List.of());
        when(taxRepository.findActiveTaxesForAll(TENANT_ID)).thenReturn(List.of(fixed));

        BigDecimal result = taxService.calculateTax(TENANT_ID, buildProduct(PRODUCT_ID), new BigDecimal("200.00"));

        assertThat(result).isEqualByComparingTo("10.00"); // fixed — ignora subtotal
    }

    @Test
    void calculateTax_roundsHalfUp() {
        Tax isv = buildTax("PERCENTAGE", "ALL", new BigDecimal("15"));
        when(taxRepository.findActiveTaxesForProduct(TENANT_ID, PRODUCT_ID)).thenReturn(List.of());
        when(taxRepository.findActiveTaxesForAll(TENANT_ID)).thenReturn(List.of(isv));

        // 33.33 * 15% = 4.9995 → se redondea a 5.00
        BigDecimal result = taxService.calculateTax(TENANT_ID, buildProduct(PRODUCT_ID), new BigDecimal("33.33"));

        assertThat(result).isEqualByComparingTo("5.00");
    }

    // ── calculateTax — prioridad PRODUCT > CATEGORY > ALL ────────────────────

    @Test
    void calculateTax_productTaxOverridesAll() {
        Tax productTax = buildTax("PERCENTAGE", "PRODUCT", new BigDecimal("5"));
        Tax globalTax  = buildTax("PERCENTAGE", "ALL",     new BigDecimal("15"));

        when(taxRepository.findActiveTaxesForProduct(TENANT_ID, PRODUCT_ID)).thenReturn(List.of(productTax));
        // globalTax no debe consultarse

        BigDecimal result = taxService.calculateTax(TENANT_ID, buildProduct(PRODUCT_ID), new BigDecimal("100.00"));

        assertThat(result).isEqualByComparingTo("5.00");
        verify(taxRepository, never()).findActiveTaxesForAll(any());
    }

    @Test
    void calculateTax_categoryTaxOverridesAll_whenNoProductTax() {
        Tax categoryTax = buildTax("PERCENTAGE", "CATEGORY", new BigDecimal("8"));
        Tax globalTax   = buildTax("PERCENTAGE", "ALL",      new BigDecimal("15"));

        when(taxRepository.findActiveTaxesForProduct(TENANT_ID, PRODUCT_ID)).thenReturn(List.of());
        when(taxRepository.findActiveTaxesForCategory(TENANT_ID, CATEGORY_ID)).thenReturn(List.of(categoryTax));

        BigDecimal result = taxService.calculateTax(TENANT_ID,
                buildProductWithCategory(PRODUCT_ID, CATEGORY_ID), new BigDecimal("100.00"));

        assertThat(result).isEqualByComparingTo("8.00");
        verify(taxRepository, never()).findActiveTaxesForAll(any());
    }

    @Test
    void calculateTax_fallsBackToAll_whenNoCategoryTax() {
        Tax globalTax = buildTax("PERCENTAGE", "ALL", new BigDecimal("15"));

        when(taxRepository.findActiveTaxesForProduct(TENANT_ID, PRODUCT_ID)).thenReturn(List.of());
        when(taxRepository.findActiveTaxesForCategory(TENANT_ID, CATEGORY_ID)).thenReturn(List.of());
        when(taxRepository.findActiveTaxesForAll(TENANT_ID)).thenReturn(List.of(globalTax));

        BigDecimal result = taxService.calculateTax(TENANT_ID,
                buildProductWithCategory(PRODUCT_ID, CATEGORY_ID), new BigDecimal("100.00"));

        assertThat(result).isEqualByComparingTo("15.00");
    }

    @Test
    void calculateTax_skipsCategory_whenProductHasNoCategory() {
        // Producto sin categoría → pasa directamente de PRODUCT a ALL
        Tax globalTax = buildTax("PERCENTAGE", "ALL", new BigDecimal("15"));

        when(taxRepository.findActiveTaxesForProduct(TENANT_ID, PRODUCT_ID)).thenReturn(List.of());
        when(taxRepository.findActiveTaxesForAll(TENANT_ID)).thenReturn(List.of(globalTax));

        BigDecimal result = taxService.calculateTax(TENANT_ID, buildProduct(PRODUCT_ID), new BigDecimal("100.00"));

        assertThat(result).isEqualByComparingTo("15.00");
        verify(taxRepository, never()).findActiveTaxesForCategory(any(), any());
    }

    // ── CRUD — findAll / findActive ───────────────────────────────────────────

    @Test
    void findAll_filtersbyTenantId() {
        when(taxRepository.findByTenantId(TENANT_ID)).thenReturn(List.of(
                buildTax("PERCENTAGE", "ALL", new BigDecimal("15"))));

        var result = taxService.findAll();

        assertThat(result).hasSize(1);
        verify(taxRepository).findByTenantId(TENANT_ID);
    }

    @Test
    void findActive_filtersActiveOnly() {
        when(taxRepository.findByTenantIdAndActiveTrue(TENANT_ID)).thenReturn(List.of());

        taxService.findActive();

        verify(taxRepository).findByTenantIdAndActiveTrue(TENANT_ID);
        verify(taxRepository, never()).findByTenantId(any());
    }

    // ── CRUD — create / toggle / delete ──────────────────────────────────────

    @Test
    void create_setsTenantIdFromContext() {
        Tax saved = buildTax("PERCENTAGE", "ALL", new BigDecimal("15"));
        saved.setId(1L);
        when(taxRepository.save(any())).thenReturn(saved);

        var dto = new balance.tax.dto.TaxDTO();
        dto.setName("ISV");
        dto.setRate(new BigDecimal("15"));
        dto.setType("PERCENTAGE");
        dto.setAppliesTo("ALL");

        taxService.create(dto);

        var captor = org.mockito.ArgumentCaptor.forClass(Tax.class);
        verify(taxRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void toggle_flipsActiveStatus() {
        Tax tax = buildTax("PERCENTAGE", "ALL", new BigDecimal("15"));
        tax.setId(1L);
        tax.setActive(true);
        when(taxRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(tax));
        when(taxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = taxService.toggle(1L);

        assertThat(result).isPresent();
        assertThat(tax.getActive()).isFalse();
    }

    @Test
    void toggle_returnsEmpty_whenTaxNotFound() {
        when(taxRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThat(taxService.toggle(99L)).isEmpty();
    }

    @Test
    void delete_returnsFalse_whenTaxNotFound() {
        when(taxRepository.findByIdAndTenantId(99L, TENANT_ID)).thenReturn(Optional.empty());

        assertThat(taxService.delete(99L)).isFalse();
        verify(taxRepository, never()).delete(any());
    }

    @Test
    void delete_returnsTrue_andDeletes_whenFound() {
        Tax tax = buildTax("PERCENTAGE", "ALL", new BigDecimal("15"));
        tax.setId(1L);
        when(taxRepository.findByIdAndTenantId(1L, TENANT_ID)).thenReturn(Optional.of(tax));

        assertThat(taxService.delete(1L)).isTrue();
        verify(taxRepository).delete(tax);
    }
}
