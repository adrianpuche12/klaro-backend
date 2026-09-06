package balance.users.model;

import balance.common.enums.AppUserStatus;
import balance.model.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, unique = true)
    private String keycloakId;

    @NotBlank
    @Column(nullable = false)
    private String fullName;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String username;

    /** Local principal. Nullable desde SPRINT-09: un perfil de solo lectura
     * (contador, socio) puede no tener un local principal fijo y depender
     * solo de {@link #accessibleStores}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AppUserStatus status = AppUserStatus.ACTIVE;

    /** Etiqueta de negocio libre (ej. "ENCARGADO", "CONTADOR", "SOCIO").
     * Solo UX/defaults al crear el usuario — nunca gatea acceso por sí sola,
     * eso lo hacen {@link #permissions} y {@link #accessibleStores} vía PermissionGuard.
     * Distinto del rol jerárquico de Keycloak (root/admin/user). */
    @Column(name = "business_role", length = 50)
    private String businessRole;

    /** Módulos habilitados (valores de {@link PermissionModule#name()}).
     * Vacío = sin acceso a ningún módulo restringido — salvo la excepción legacy
     * en PermissionGuard (usuario sin businessRole ni filas nuevas = acceso total,
     * igual que antes de este sprint). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission")
    private Set<String> permissions = new HashSet<>();

    /** Locales cuya data puede ver este usuario. Vacío = sin acceso a ningún
     * local — misma excepción legacy que arriba aplica en PermissionGuard. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_store_access",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "store_id")
    )
    private Set<Store> accessibleStores = new HashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
