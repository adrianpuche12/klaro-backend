package balance.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    @NotBlank(message = "El campo 'type' no puede estar vacío")
    @Pattern(regexp = "income|expense", message = "El campo 'type' debe ser 'income' o 'expense'")
    private String type; // "income" o "expense"

    @Column(nullable = false)
    @NotNull(message = "El campo 'amount' no puede ser nulo")
    @DecimalMin(value = "0.01", message = "El monto debe ser mayor a 0")
    private BigDecimal amount;

    @Column(nullable = false)
    @NotNull(message = "El campo 'date' no puede ser nulo")
    @PastOrPresent(message = "La fecha no puede estar en el futuro")
    private LocalDate date;

    @Column
    @Size(max = 255, message = "La descripción no puede tener más de 255 caracteres")
    private String description;

    @ManyToOne
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    // Referencia opcional al GastoAdmin que originó esta transacción (null si es directa)
    @Column(name = "gasto_admin_id")
    private Long gastoAdminId;

    @Column(name = "image_uri", length = 512)
    private String imageUri;

    // Getters y Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public Long getGastoAdminId() {
        return gastoAdminId;
    }

    public void setGastoAdminId(Long gastoAdminId) {
        this.gastoAdminId = gastoAdminId;
    }

    public String getImageUri() { return imageUri; }
    public void setImageUri(String imageUri) { this.imageUri = imageUri; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}
