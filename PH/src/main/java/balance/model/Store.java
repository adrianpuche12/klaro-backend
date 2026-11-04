package balance.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "store")
// Transaction, ClosingDeposit, SupplierPayment y SalaryPayment se serializan con su Store. Si el
// mismo local ya está en la sesión como proxy LAZY (ej. AppUser.store, que carga PermissionGuard
// para todo usuario no-root), Jackson recibía el proxy y fallaba con 500 (lo encontró
// PermissionMatrixIT: encargado y contador no podían listar Finanzas ni Operaciones).
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @NotBlank(message = "El nombre es obligatorio")
    @Column(nullable = false)
    private String name;

    private String address;

    private String phone;

    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
