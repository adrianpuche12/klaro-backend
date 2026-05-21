package balance.service;

import balance.dto.AllOperationsDTO;
import balance.dto.GastoAdminRequestDTO;
import balance.dto.GastoAdminResponseDTO;
import balance.model.*;
import balance.repository.*;
import balance.tenant.context.TenantSecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class FormsService {

    @Autowired private GastoAdminRepository gastoAdminRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private ClosingDepositRepository closingDepositRepository;
    @Autowired private SupplierPaymentRepository supplierPaymentRepository;
    @Autowired private SalaryPaymentRepository salaryPaymentRepository;

    // ── Operaciones combinadas ─────────────────────────────────────────────

    public List<AllOperationsDTO> getAllOperations() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        List<AllOperationsDTO> result = new ArrayList<>();
        closingDepositRepository.findByTenantIdOrderByDepositDateDesc(tenantId)
                .stream().map(AllOperationsDTO::fromClosingDeposit).forEach(result::add);
        supplierPaymentRepository.findByTenantIdOrderByPaymentDateDesc(tenantId)
                .stream().map(AllOperationsDTO::fromSupplierPayment).forEach(result::add);
        salaryPaymentRepository.findByTenantIdOrderBySalaryDateDesc(tenantId)
                .stream().map(AllOperationsDTO::fromSalaryPayment).forEach(result::add);
        result.sort((a, b) -> {
            if (a.getDate() == null) return 1;
            if (b.getDate() == null) return -1;
            return b.getDate().compareTo(a.getDate());
        });
        return result;
    }

    public List<AllOperationsDTO> getOperationsByDateRange(LocalDate startDate, LocalDate endDate) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        List<AllOperationsDTO> result = new ArrayList<>();
        closingDepositRepository.findByTenantIdAndDateRange(tenantId, startDate, endDate)
                .stream().map(AllOperationsDTO::fromClosingDeposit).forEach(result::add);
        supplierPaymentRepository.findByTenantIdAndDateRange(tenantId, startDate, endDate)
                .stream().map(AllOperationsDTO::fromSupplierPayment).forEach(result::add);
        salaryPaymentRepository.findByTenantIdAndDateRange(tenantId, startDate, endDate)
                .stream().map(AllOperationsDTO::fromSalaryPayment).forEach(result::add);
        result.sort((a, b) -> {
            if (a.getDate() == null) return 1;
            if (b.getDate() == null) return -1;
            return b.getDate().compareTo(a.getDate());
        });
        return result;
    }

    public List<AllOperationsDTO> getOperationsByStore(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        List<AllOperationsDTO> result = new ArrayList<>();
        closingDepositRepository.findByStoreIdAndTenantIdOrderByDepositDateDesc(storeId, tenantId)
                .stream().map(AllOperationsDTO::fromClosingDeposit).forEach(result::add);
        supplierPaymentRepository.findByStoreIdAndTenantIdOrderByPaymentDateDesc(storeId, tenantId)
                .stream().map(AllOperationsDTO::fromSupplierPayment).forEach(result::add);
        salaryPaymentRepository.findByStoreIdAndTenantIdOrderBySalaryDateDesc(storeId, tenantId)
                .stream().map(AllOperationsDTO::fromSalaryPayment).forEach(result::add);
        result.sort((a, b) -> {
            if (a.getDate() == null) return 1;
            if (b.getDate() == null) return -1;
            return b.getDate().compareTo(a.getDate());
        });
        return result;
    }

    public List<AllOperationsDTO> getOperationsByDateRangeAndStore(
            LocalDate startDate, LocalDate endDate, Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        List<AllOperationsDTO> result = new ArrayList<>();
        closingDepositRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, startDate, endDate)
                .stream().map(AllOperationsDTO::fromClosingDeposit).forEach(result::add);
        supplierPaymentRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, startDate, endDate)
                .stream().map(AllOperationsDTO::fromSupplierPayment).forEach(result::add);
        salaryPaymentRepository.findByStoreIdAndTenantIdAndDateRange(storeId, tenantId, startDate, endDate)
                .stream().map(AllOperationsDTO::fromSalaryPayment).forEach(result::add);
        result.sort((a, b) -> {
            if (a.getDate() == null) return 1;
            if (b.getDate() == null) return -1;
            return b.getDate().compareTo(a.getDate());
        });
        return result;
    }

    public List<AllOperationsDTO> getOperationsByUsername(String username) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        List<AllOperationsDTO> result = new ArrayList<>();
        closingDepositRepository.findByUsernameAndTenantIdOrderByDepositDateDesc(username, tenantId)
                .stream().map(AllOperationsDTO::fromClosingDeposit).forEach(result::add);
        supplierPaymentRepository.findByUsernameAndTenantIdOrderByPaymentDateDesc(username, tenantId)
                .stream().map(AllOperationsDTO::fromSupplierPayment).forEach(result::add);
        salaryPaymentRepository.findByUsernameAndTenantIdOrderBySalaryDateDesc(username, tenantId)
                .stream().map(AllOperationsDTO::fromSalaryPayment).forEach(result::add);
        result.sort((a, b) -> {
            if (a.getDate() == null) return 1;
            if (b.getDate() == null) return -1;
            return b.getDate().compareTo(a.getDate());
        });
        return result;
    }

    // ── ClosingDeposit ────────────────────────────────────────────────────

    /** Usado internamente por SalesService — el tenantId ya viene seteado en el objeto. */
    public ClosingDeposit saveClosingDeposit(ClosingDeposit deposit) {
        if (deposit.getDepositDate() == null) deposit.setDepositDate(LocalDate.now());
        return closingDepositRepository.save(deposit);
    }

    public List<ClosingDeposit> getAllClosingDeposits() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return closingDepositRepository.findByTenantIdOrderByDepositDateDesc(tenantId);
    }

    public List<ClosingDeposit> getClosingDeposits(LocalDate startDate, LocalDate endDate) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return closingDepositRepository.findByTenantIdAndDateRange(tenantId, startDate, endDate);
    }

    public List<ClosingDeposit> findByStoreId(Long storeId) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        TenantSecurityUtils.requireStore(storeId, tenantId, storeRepository);
        return closingDepositRepository.findByStoreIdAndTenantIdOrderByDepositDateDesc(storeId, tenantId);
    }

    public ClosingDeposit updateClosingDeposit(Long id, ClosingDeposit updatedDeposit) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        ClosingDeposit existing = closingDepositRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "ClosingDeposit no encontrado con id " + id));
        existing.setAmount(updatedDeposit.getAmount());
        existing.setUsername(updatedDeposit.getUsername());
        if (updatedDeposit.getClosingsCount() != null) existing.setClosingsCount(updatedDeposit.getClosingsCount());
        if (updatedDeposit.getPeriodStart()   != null) existing.setPeriodStart(updatedDeposit.getPeriodStart());
        if (updatedDeposit.getPeriodEnd()     != null) existing.setPeriodEnd(updatedDeposit.getPeriodEnd());
        if (updatedDeposit.getDepositDate()   != null) existing.setDepositDate(updatedDeposit.getDepositDate());
        if (updatedDeposit.getStore()         != null) {
            TenantSecurityUtils.requireStore(updatedDeposit.getStore().getId(), tenantId, storeRepository);
            existing.setStore(updatedDeposit.getStore());
        }
        return closingDepositRepository.save(existing);
    }

    public void deleteClosingDeposit(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        closingDepositRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "ClosingDeposit no encontrado con id " + id));
        closingDepositRepository.deleteById(id);
    }

    // ── SupplierPayment ───────────────────────────────────────────────────

    public SupplierPayment saveSupplierPayment(SupplierPayment payment) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        if (payment.getPaymentDate() == null) payment.setPaymentDate(LocalDate.now());
        payment.setTenantId(tenantId);
        return supplierPaymentRepository.save(payment);
    }

    public List<SupplierPayment> getAllSupplierPayments() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return supplierPaymentRepository.findByTenantIdOrderByPaymentDateDesc(tenantId);
    }

    public List<SupplierPayment> getSupplierPayments(LocalDate startDate, LocalDate endDate) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return supplierPaymentRepository.findByTenantIdAndDateRange(tenantId, startDate, endDate);
    }

    public SupplierPayment updateSupplierPayment(Long id, SupplierPayment updatedPayment) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        SupplierPayment existing = supplierPaymentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "SupplierPayment no encontrado con id " + id));
        existing.setAmount(updatedPayment.getAmount());
        if (updatedPayment.getDescription() != null) existing.setDescription(updatedPayment.getDescription());
        if (updatedPayment.getUsername()    != null) existing.setUsername(updatedPayment.getUsername());
        if (updatedPayment.getSupplier()    != null) existing.setSupplier(updatedPayment.getSupplier());
        if (updatedPayment.getPaymentDate() != null) existing.setPaymentDate(updatedPayment.getPaymentDate());
        if (updatedPayment.getStore()       != null) {
            TenantSecurityUtils.requireStore(updatedPayment.getStore().getId(), tenantId, storeRepository);
            existing.setStore(updatedPayment.getStore());
        }
        return supplierPaymentRepository.save(existing);
    }

    public void deleteSupplierPayment(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        supplierPaymentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "SupplierPayment no encontrado con id " + id));
        supplierPaymentRepository.deleteById(id);
    }

    // ── SalaryPayment ─────────────────────────────────────────────────────

    public SalaryPayment saveSalaryPayment(SalaryPayment payment) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        if (payment.getSalaryDate() == null) payment.setSalaryDate(LocalDate.now());
        payment.setTenantId(tenantId);
        return salaryPaymentRepository.save(payment);
    }

    public List<SalaryPayment> getAllSalaryPayments() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return salaryPaymentRepository.findByTenantIdOrderBySalaryDateDesc(tenantId);
    }

    public SalaryPayment updateSalaryPayment(Long id, SalaryPayment updatedPayment) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        SalaryPayment existing = salaryPaymentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "SalaryPayment no encontrado con id " + id));
        existing.setAmount(updatedPayment.getAmount());
        if (updatedPayment.getDescription() != null) existing.setDescription(updatedPayment.getDescription());
        if (updatedPayment.getUsername()    != null) existing.setUsername(updatedPayment.getUsername());
        if (updatedPayment.getSalaryDate()  != null) existing.setSalaryDate(updatedPayment.getSalaryDate());
        if (updatedPayment.getStore()       != null) {
            TenantSecurityUtils.requireStore(updatedPayment.getStore().getId(), tenantId, storeRepository);
            existing.setStore(updatedPayment.getStore());
        }
        return salaryPaymentRepository.save(existing);
    }

    public void deleteSalaryPayment(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        salaryPaymentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "SalaryPayment no encontrado con id " + id));
        salaryPaymentRepository.deleteById(id);
    }

    // ── GastoAdmin ────────────────────────────────────────────────────────

    public GastoAdminResponseDTO saveGastoAdmin(GastoAdminRequestDTO request) {
        Long tenantId = TenantSecurityUtils.requireTenantId();

        if (!request.isValidPercentages()) {
            throw new IllegalArgumentException("Los porcentajes deben sumar exactamente 100%");
        }

        GastoAdmin gastoAdmin = new GastoAdmin();
        gastoAdmin.setFecha(request.getFecha());
        gastoAdmin.setMonto(request.getMonto());
        gastoAdmin.setDescripcion(request.getDescripcion());
        gastoAdmin.setUsername("admin_user");
        gastoAdmin.setPorcentajeDanli(0);
        gastoAdmin.setPorcentajeParaiso(0);
        gastoAdmin.setMontoDanli(BigDecimal.ZERO);
        gastoAdmin.setMontoParaiso(BigDecimal.ZERO);
        gastoAdmin.setTenantId(tenantId);
        GastoAdmin gastoAdminSaved = gastoAdminRepository.save(gastoAdmin);

        List<GastoAdminResponseDTO.TransaccionCreada> transaccionesCreadas = new ArrayList<>();

        for (GastoAdminRequestDTO.StoreDistribucion dist : request.getDistribuciones()) {
            Store store = TenantSecurityUtils.requireStore(dist.getStoreId(), tenantId, storeRepository);
            BigDecimal montoLocal = calcularMonto(request.getMonto(), dist.getPorcentaje());

            Transaction tx = new Transaction();
            tx.setType(request.getTipo());
            tx.setAmount(montoLocal);
            tx.setDate(request.getFecha());
            tx.setDescription(String.format("%s (%s %d%%)",
                    request.getDescripcion(), store.getName(), dist.getPorcentaje()));
            tx.setStore(store);
            tx.setGastoAdminId(gastoAdminSaved.getId());
            tx.setTenantId(tenantId);
            Transaction saved = transactionRepository.save(tx);

            transaccionesCreadas.add(new GastoAdminResponseDTO.TransaccionCreada(
                    saved.getId(), saved.getType(), saved.getAmount(),
                    saved.getDate(), saved.getDescription(),
                    store.getName(), dist.getPorcentaje()));
        }

        return new GastoAdminResponseDTO(
                "Gasto administrativo creado exitosamente. Se crearon " + transaccionesCreadas.size() + " transacciones.",
                transaccionesCreadas.size(), request.getMonto(), transaccionesCreadas, gastoAdminSaved.getId());
    }

    public List<GastoAdmin> getAllGastosAdmin() {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return gastoAdminRepository.findByTenantIdOrderByFechaDesc(tenantId);
    }

    public List<GastoAdmin> getGastosAdmin(LocalDate startDate, LocalDate endDate) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        return gastoAdminRepository.findByTenantIdAndDateRange(tenantId, startDate, endDate);
    }

    public GastoAdmin updateGastoAdmin(Long id, GastoAdmin updatedGastoAdmin) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        GastoAdmin existing = gastoAdminRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "GastoAdmin no encontrado con id " + id));
        if (updatedGastoAdmin.getMonto()      != null) existing.setMonto(updatedGastoAdmin.getMonto());
        if (updatedGastoAdmin.getDescripcion()!= null) existing.setDescripcion(updatedGastoAdmin.getDescripcion());
        if (updatedGastoAdmin.getFecha()      != null) existing.setFecha(updatedGastoAdmin.getFecha());
        if (updatedGastoAdmin.getUsername()   != null) existing.setUsername(updatedGastoAdmin.getUsername());
        return gastoAdminRepository.save(existing);
    }

    @Transactional
    public GastoAdminResponseDTO updateGastoAdminV2(Long id, GastoAdminRequestDTO request) {
        Long tenantId = TenantSecurityUtils.requireTenantId();

        if (!request.isValidPercentages()) {
            throw new IllegalArgumentException("Los porcentajes deben sumar exactamente 100%");
        }

        GastoAdmin existing = gastoAdminRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "GastoAdmin no encontrado con id " + id));

        transactionRepository.deleteByGastoAdminId(id);

        existing.setFecha(request.getFecha());
        existing.setMonto(request.getMonto());
        existing.setDescripcion(request.getDescripcion());
        gastoAdminRepository.save(existing);

        List<GastoAdminResponseDTO.TransaccionCreada> transaccionesCreadas = new ArrayList<>();

        for (GastoAdminRequestDTO.StoreDistribucion dist : request.getDistribuciones()) {
            Store store = TenantSecurityUtils.requireStore(dist.getStoreId(), tenantId, storeRepository);
            BigDecimal montoLocal = calcularMonto(request.getMonto(), dist.getPorcentaje());

            Transaction tx = new Transaction();
            tx.setType(request.getTipo());
            tx.setAmount(montoLocal);
            tx.setDate(request.getFecha());
            tx.setDescription(String.format("%s (%s %d%%)",
                    request.getDescripcion(), store.getName(), dist.getPorcentaje()));
            tx.setStore(store);
            tx.setGastoAdminId(id);
            tx.setTenantId(tenantId);
            Transaction saved = transactionRepository.save(tx);

            transaccionesCreadas.add(new GastoAdminResponseDTO.TransaccionCreada(
                    saved.getId(), saved.getType(), saved.getAmount(),
                    saved.getDate(), saved.getDescription(),
                    store.getName(), dist.getPorcentaje()));
        }

        return new GastoAdminResponseDTO(
                "Gasto administrativo actualizado. Se recrearon " + transaccionesCreadas.size() + " transacciones.",
                transaccionesCreadas.size(), request.getMonto(), transaccionesCreadas, id);
    }

    public void deleteGastoAdmin(Long id) {
        Long tenantId = TenantSecurityUtils.requireTenantId();
        if (!gastoAdminRepository.existsByIdAndTenantId(id, tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "GastoAdmin no encontrado con id " + id);
        }
        gastoAdminRepository.deleteById(id);
    }

    private BigDecimal calcularMonto(BigDecimal montoTotal, Integer porcentaje) {
        return montoTotal.multiply(BigDecimal.valueOf(porcentaje))
                .divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
    }
}
