package balance.sales.model;

import balance.common.enums.ShiftStatus;
import balance.model.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
// Código único POR EMPRESA (V16: ux_shifts_tenant_code). Antes era único en toda la base y dos
// empresas con locales del mismo prefijo chocaban al abrir en el mismo minuto.
@Table(name = "shifts",
       uniqueConstraints = @UniqueConstraint(name = "ux_shifts_tenant_code", columnNames = {"tenant_id", "code"}))
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String code;

    @NotBlank
    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ShiftStatus status = ShiftStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime openedAt;

    private LocalDateTime closedAt;

    /** Observación opcional del empleado al cerrar el turno -- SPRINT-12. */
    @Column(length = 500)
    private String notes;
}
