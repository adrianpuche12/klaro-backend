package balance.sales.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Clave de idempotencia de una venta (V15). Ver {@link balance.sales.service.SalesService#createSaleIdempotent}. */
@Getter
@Setter
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "sale_id")
    private Long saleId;

    @Column(name = "response", columnDefinition = "text")
    private String response;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
