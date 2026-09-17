package balance.sales.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Salida de caja durante un turno abierto (ej. pago a un repartidor) -- SPRINT-12. */
@Getter
@Setter
@Entity
@Table(name = "shift_expenses")
public class ShiftExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @NotNull(message = "El monto es obligatorio")
    @DecimalMin(value = "0.01", message = "El monto debe ser mayor a 0")
    @Column(nullable = false)
    private BigDecimal amount;

    @NotBlank(message = "El motivo es obligatorio")
    @Column(nullable = false)
    private String reason;

    @NotBlank(message = "El nombre de usuario es obligatorio")
    @Column(nullable = false)
    private String username;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
