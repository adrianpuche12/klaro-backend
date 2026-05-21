package balance.tax.service;

import balance.catalog.model.Product;
import balance.catalog.repository.ProductRepository;
import balance.tax.dto.TaxDTO;
import balance.tax.model.Tax;
import balance.tax.repository.TaxRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Service
public class TaxService {

    @Autowired private TaxRepository taxRepository;
    @Autowired private ProductRepository productRepository;

    public List<TaxDTO> findAll() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return taxRepository.findByTenantId(tenantId).stream().map(TaxDTO::from).toList();
    }

    public List<TaxDTO> findActive() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return taxRepository.findByTenantIdAndActiveTrue(tenantId).stream().map(TaxDTO::from).toList();
    }

    @Transactional
    public TaxDTO create(TaxDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Tax tax = new Tax();
        tax.setTenantId(tenantId);
        applyDTO(tax, dto);
        return TaxDTO.from(taxRepository.save(tax));
    }

    @Transactional
    public Optional<TaxDTO> update(Long id, TaxDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return taxRepository.findByIdAndTenantId(id, tenantId).map(tax -> {
            applyDTO(tax, dto);
            return TaxDTO.from(taxRepository.save(tax));
        });
    }

    @Transactional
    public Optional<TaxDTO> toggle(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return taxRepository.findByIdAndTenantId(id, tenantId).map(tax -> {
            tax.setActive(!Boolean.TRUE.equals(tax.getActive()));
            return TaxDTO.from(taxRepository.save(tax));
        });
    }

    @Transactional
    public boolean delete(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return taxRepository.findByIdAndTenantId(id, tenantId).map(tax -> {
            taxRepository.delete(tax);
            return true;
        }).orElse(false);
    }

    /**
     * Calcula el monto de impuesto para un producto dado.
     * Prioridad: PRODUCT > CATEGORY > ALL
     * Si no hay impuesto → BigDecimal.ZERO
     */
    public BigDecimal calculateTax(Long tenantId, Product product, BigDecimal subtotal) {
        // 1. Impuesto específico del producto
        List<Tax> taxes = taxRepository.findActiveTaxesForProduct(tenantId, product.getId());

        // 2. Si no hay por producto, buscar por categoría
        if (taxes.isEmpty() && product.getCategory() != null) {
            taxes = taxRepository.findActiveTaxesForCategory(tenantId, product.getCategory().getId());
        }

        // 3. Si no hay por categoría, usar globales
        if (taxes.isEmpty()) {
            taxes = taxRepository.findActiveTaxesForAll(tenantId);
        }

        // Aplicar el primer impuesto encontrado (la prioridad ya está resuelta)
        if (taxes.isEmpty()) return BigDecimal.ZERO;

        Tax tax = taxes.get(0);
        if ("PERCENTAGE".equals(tax.getType())) {
            return subtotal.multiply(tax.getRate().divide(BigDecimal.valueOf(100)))
                    .setScale(2, RoundingMode.HALF_UP);
        } else { // FIXED
            return tax.getRate().setScale(2, RoundingMode.HALF_UP);
        }
    }

    private void applyDTO(Tax tax, TaxDTO dto) {
        if (dto.getName()       != null) tax.setName(dto.getName().trim());
        if (dto.getRate()       != null) tax.setRate(dto.getRate());
        if (dto.getType()       != null) tax.setType(dto.getType());
        if (dto.getAppliesTo()  != null) tax.setAppliesTo(dto.getAppliesTo());
        tax.setCategoryId(dto.getCategoryId());
        tax.setProductId(dto.getProductId());
    }
}
