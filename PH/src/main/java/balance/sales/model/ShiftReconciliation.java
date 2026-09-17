package balance.sales.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Reconciliación de caja de un turno cerrado -- SPRINT-12.
 * {@code expectedCash} y {@code difference} se calculan una sola vez al
 * cerrar y se persisten (no se recalculan en cada lectura) para que el
 * historial refleje exactamente lo que se cuadró ese día.
 */
@Getter
@Setter
@Entity
@Table(name = "shift_reconciliations")
public class ShiftReconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @OneToOne
    @JoinColumn(name = "shift_id", nullable = false, unique = true)
    private Shift shift;

    /** Efectivo con el que se abrió el turno (fondo inicial). */
    @Column(name = "opening_cash", nullable = false)
    private BigDecimal openingCash;

    /** Efectivo contado físicamente al cerrar. */
    @Column(name = "declared_cash", nullable = false)
    private BigDecimal declaredCash;

    /** openingCash + ventas en efectivo del turno - egresos del turno. */
    @Column(name = "expected_cash", nullable = false)
    private BigDecimal expectedCash;

    /** declaredCash - expectedCash. Positiva = sobra, negativa = falta, cero = cuadra. */
    @Column(nullable = false)
    private BigDecimal difference;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
