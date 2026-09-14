package balance.deposit.model;

import balance.model.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Agrupa uno o más {@link balance.model.ClosingDeposit} (cierres de caja) en
 * un solo depósito físico al banco -- SPRINT-11. El monto que cuenta para
 * reportes/reconciliación es {@code declaredAmount} (lo que efectivamente se
 * depositó), no la suma recalculada de los cierres, para reflejar diferencias
 * reales de caja.
 */
@Getter
@Setter
@Entity
@Table(name = "bank_deposits")
public class BankDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @NotNull(message = "La fecha del depósito es obligatoria")
    @Column(name = "deposit_date", nullable = false)
    private LocalDate depositDate;

    @NotNull(message = "El monto declarado es obligatorio")
    @DecimalMin(value = "0.01", message = "El monto declarado debe ser mayor a 0")
    @Column(name = "declared_amount", nullable = false)
    private BigDecimal declaredAmount;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "image_uri", length = 512)
    private String imageUri;

    @NotBlank(message = "El nombre de usuario es obligatorio")
    @Column(nullable = false)
    private String username;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
