package balance.users.model;

import balance.common.enums.AppUserStatus;
import balance.model.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** ID del usuario dentro de Keycloak (UUID). */
    @Column(nullable = false, unique = true)
    private String keycloakId;

    /** Nombre completo del empleado (ej: "María López"). */
    @NotBlank
    @Column(nullable = false)
    private String fullName;

    /** Username para login (ej: "maria.lopez"). Único en el realm. */
    @NotBlank
    @Column(nullable = false, unique = true)
    private String username;

    /** Local al que está asignado el usuario. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** ACTIVE = puede operar / SUSPENDED = no puede iniciar sesión. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AppUserStatus status = AppUserStatus.ACTIVE;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public Long getId()                    { return id; }

    public String getKeycloakId()          { return keycloakId; }
    public void setKeycloakId(String v)    { this.keycloakId = v; }

    public String getFullName()            { return fullName; }
    public void setFullName(String v)      { this.fullName = v; }

    public String getUsername()            { return username; }
    public void setUsername(String v)      { this.username = v; }

    public Store getStore()                { return store; }
    public void setStore(Store v)          { this.store = v; }

    public AppUserStatus getStatus()         { return status; }
    public void setStatus(AppUserStatus v)  { this.status = v; }

    public LocalDateTime getCreatedAt()    { return createdAt; }

    public Long getTenantId()              { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}
