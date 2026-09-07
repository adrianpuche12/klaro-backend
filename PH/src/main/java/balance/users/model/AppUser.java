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

    /** @deprecated SPRINT-14: reemplazado por {@link #role}. Se conserva la
     * columna (no se borra) porque la migración V5 la usó como fuente para
     * crear los Roles equivalentes de usuarios ya existentes — no se lee más
     * en ningún chequeo de acceso nuevo. */
    @Deprecated
    @Column(name = "business_role", length = 50)
    private String businessRole;

    /** @deprecated SPRINT-14: reemplazado por {@link Role#getPermissions()}.
     * Se conserva la tabla (no se borra) por la misma razón que {@link #businessRole}. */
    @Deprecated
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission")
    private Set<String> permissions = new HashSet<>();

    /** Role configurable (SPRINT-14) — define qué módulos ve este usuario.
     * {@code null} = root, o usuario legacy sin perfil acotado (excepción
     * legacy en PermissionGuard, acceso total, igual que antes). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;

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
