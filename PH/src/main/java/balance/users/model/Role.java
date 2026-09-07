package balance.users.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Role configurable definido por root (SPRINT-14). Reemplaza el business_role
 * libre de SPRINT-09 y el rol fijo Keycloak "admin" — root crea la cantidad
 * de Roles que quiera, con el nombre que quiera, y les asigna módulos.
 *
 * Root en sí mismo NUNCA es una fila de esta tabla: sigue siendo el rol
 * Keycloak "root", nivel implícito 0, acceso total siempre, sin excepciones.
 */
@Getter
@Setter
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @NotBlank
    @Column(nullable = false, length = 100)
    private String name;

    /** Nivel jerárquico, mayor a 0. Un usuario solo puede gestionar Roles
     * de nivel estrictamente mayor al suyo — nunca su propio nivel ni uno menor. */
    @Column(nullable = false)
    private int level;

    /** Si es true, un usuario con este Role puede crear/suspender/eliminar
     * usuarios cuyo Role tenga nivel mayor al de este Role. Independiente
     * de los módulos de negocio — un Role puede tener ambas cosas o ninguna. */
    @Column(name = "can_manage_users", nullable = false)
    private boolean canManageUsers = false;

    @Column(name = "created_by")
    private Long createdBy;

    /** Módulos habilitados (valores de {@link PermissionModule#name()}). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission")
    private Set<String> permissions = new HashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
