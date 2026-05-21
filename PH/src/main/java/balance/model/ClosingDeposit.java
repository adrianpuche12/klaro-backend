package balance.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

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



    // Getters and setters


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getClosingsCount() {
        return closingsCount;
    }

    public void setClosingsCount(Integer closingsCount) {
        this.closingsCount = closingsCount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getDepositDate() {
        return depositDate;
    }

    public void setDepositDate(LocalDate depositDate) {
        this.depositDate = depositDate;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public String getImageUri() { return imageUri; }
    public void setImageUri(String imageUri) { this.imageUri = imageUri; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}

