package balance.model;

import balance.deposit.model.BankDeposit;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "closing_deposits")
public class ClosingDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Min(value = 1, message = "La cantidad de cierres debe ser al menos 1")
    private Integer closingsCount;

    @NotNull(message = "El monto es obligatorio")
    @DecimalMin(value = "0.01", message = "El monto debe ser mayor a 0")
    @Column(nullable = false)
    private BigDecimal amount;

    @NotNull(message = "La fecha del cierre de deposito es obligatoria")
    @Column(nullable = false)
    private LocalDate depositDate;

    @NotNull(message = "La fecha de inicio del período es obligatoria")
    @Column(nullable = false)
    private LocalDate periodStart;

    @NotNull(message = "La fecha de fin del período es obligatoria")
    @Column(nullable = false)
    private LocalDate periodEnd;

    @NotBlank(message = "El nombre de usuario es obligatorio")
    @Column(nullable = false)
    private String username;

    @Column(name = "image_uri", length = 512)
    private String imageUri;

    @ManyToOne
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** SPRINT-11: null mientras el cierre está pendiente de agrupar en un depósito. */
    @ManyToOne
    @JoinColumn(name = "bank_deposit_id")
    private BankDeposit bankDeposit;
}
