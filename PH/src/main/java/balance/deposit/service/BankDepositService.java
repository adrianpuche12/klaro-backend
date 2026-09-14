package balance.deposit.service;

import balance.deposit.dto.BankDepositRequestDTO;
import balance.deposit.dto.BankDepositResponseDTO;
import balance.deposit.dto.BankDepositUpdateDTO;
import balance.deposit.dto.PendingClosingDTO;
import balance.deposit.model.BankDeposit;
import balance.deposit.repository.BankDepositRepository;
import balance.model.ClosingDeposit;
import balance.model.Store;
import balance.repository.ClosingDepositRepository;
import balance.repository.StoreRepository;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SPRINT-11 — agrupa {@link ClosingDeposit} (cierres de caja) en un
 * {@link BankDeposit} (depósito físico al banco). Ver
 * "sprints/SPRINT-11 - Depositos Bancarios.md" en el vault de Belopia.
 */
@Service
public class BankDepositService {

    @Autowired private BankDepositRepository     bankDepositRepository;
    @Autowired private ClosingDepositRepository  closingDepositRepository;
    @Autowired private StoreRepository           storeRepository;

    @Transactional(readOnly = true)
    public List<PendingClosingDTO> findPendingClosings(Long storeId, LocalDate periodStart, LocalDate periodEnd) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);

        return closingDepositRepository
                .findPendingByStoreIdAndTenantIdAndDateRange(storeId, tenantId, periodStart, periodEnd)
                .stream().map(PendingClosingDTO::from).toList();
    }

    @Transactional
    public BankDepositResponseDTO create(BankDepositRequestDTO dto) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        Store store = TenantSecurityUtils.requireStore(dto.getStoreId(), tenantId, storeRepository);

        List<ClosingDeposit> pending = closingDepositRepository.findPendingByStoreIdAndTenantIdAndDateRange(
                dto.getStoreId(), tenantId, dto.getPeriodStart(), dto.getPeriodEnd());
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("No hay cierres pendientes de depósito en ese período para este local");
        }

        BankDeposit deposit = new BankDeposit();
        deposit.setTenantId(tenantId);
        deposit.setStore(store);
        deposit.setDepositDate(dto.getDepositDate() != null ? dto.getDepositDate() : LocalDate.now());
        deposit.setDeclaredAmount(dto.getDeclaredAmount());
        deposit.setNotes(dto.getNotes());
        deposit.setImageUri(dto.getImageUri());
        deposit.setUsername(dto.getUsername());
        deposit = bankDepositRepository.save(deposit);

        BigDecimal expectedCash = BigDecimal.ZERO;
        for (ClosingDeposit c : pending) {
            c.setBankDeposit(deposit);
            expectedCash = expectedCash.add(c.getAmount());
        }
        closingDepositRepository.saveAll(pending);

        return BankDepositResponseDTO.of(deposit, expectedCash, pending.stream().map(ClosingDeposit::getId).toList());
    }

    @Transactional(readOnly = true)
    public List<BankDepositResponseDTO> findAll(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        List<BankDeposit> deposits = storeId != null
                ? bankDepositRepository.findByStoreIdAndTenantIdOrderByDepositDateDesc(storeId, tenantId)
                : bankDepositRepository.findByTenantIdOrderByDepositDateDesc(tenantId);
        return deposits.stream().map(this::toResponseWithLinkedClosings).toList();
    }

    @Transactional(readOnly = true)
    public BankDepositResponseDTO findById(Long id) {
        BankDeposit deposit = requireOwnDeposit(id);
        return toResponseWithLinkedClosings(deposit);
    }

    @Transactional
    public BankDepositResponseDTO update(Long id, BankDepositUpdateDTO dto) {
        BankDeposit deposit = requireOwnDeposit(id);

        if (dto.getDeclaredAmount() != null) deposit.setDeclaredAmount(dto.getDeclaredAmount());
        if (dto.getNotes() != null) deposit.setNotes(dto.getNotes());
        if (dto.getDepositDate() != null) deposit.setDepositDate(dto.getDepositDate());
        if (dto.getImageUri() != null) deposit.setImageUri(dto.getImageUri());
        deposit.setUpdatedAt(LocalDateTime.now());
        deposit = bankDepositRepository.save(deposit);

        // Propaga fecha e imagen a los cierres vinculados -- mismo criterio que PH v2.
        if (dto.getDepositDate() != null) {
            closingDepositRepository.updateDepositDateByBankDepositId(id, dto.getDepositDate());
        }
        if (dto.getImageUri() != null) {
            closingDepositRepository.updateImageUriByBankDepositId(id, dto.getImageUri());
        }

        return toResponseWithLinkedClosings(deposit);
    }

    @Transactional
    public void delete(Long id) {
        BankDeposit deposit = requireOwnDeposit(id);
        Long tenantId = TenantSecurityUtils.requireTenantId();

        // Cascada: los cierres agrupados en este depósito se eliminan con él --
        // no quedan ClosingDeposit huérfanos apuntando a un bank_deposit_id inexistente.
        List<ClosingDeposit> linked = closingDepositRepository.findByBankDepositIdAndTenantId(id, tenantId);
        closingDepositRepository.deleteAll(linked);
        bankDepositRepository.delete(deposit);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BankDeposit requireOwnDeposit(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return bankDepositRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Depósito no encontrado"));
    }

    private BankDepositResponseDTO toResponseWithLinkedClosings(BankDeposit deposit) {
        List<ClosingDeposit> linked = closingDepositRepository
                .findByBankDepositIdAndTenantId(deposit.getId(), deposit.getTenantId());
        BigDecimal expectedCash = linked.stream()
                .map(ClosingDeposit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return BankDepositResponseDTO.of(deposit, expectedCash, linked.stream().map(ClosingDeposit::getId).toList());
    }
}
