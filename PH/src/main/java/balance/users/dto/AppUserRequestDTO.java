package balance.users.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class AppUserRequestDTO {

    @NotBlank(message = "El nombre completo es obligatorio")
    private String fullName;

    @NotBlank(message = "El username es obligatorio")
    private String username;

    @NotBlank(message = "La contrasena es obligatoria")
    private String password;

    /** Local principal. Ya no es obligatorio desde SPRINT-09 — un perfil de
     * solo lectura (contador, socio) puede no tener local principal fijo y
     * depender solo de {@link #storeIds}. */
    private Long storeId;

    /** @deprecated SPRINT-14: toda cuenta creada vía este endpoint es "staff" en
     * Keycloak — la granularidad real la da {@link #roleId}. Campo ignorado. */
    @Deprecated
    private String role;

    /** @deprecated SPRINT-14: reemplazado por {@link #roleId}. Ignorado. */
    @Deprecated
    private String businessRole;

    /** @deprecated SPRINT-14: los módulos vienen del Role ({@link #roleId}), no se
     * setean sueltos por usuario. Ignorado. */
    @Deprecated
    private List<String> permissions;

    /** Role a asignar (SPRINT-14) — define qué módulos ve. {@code null} = sin
     * Role (acceso total) — solo root puede crear así. */
    private Long roleId;

    /** Locales adicionales accesibles (más allá del local principal). Opcional. */
    private List<Long> storeIds;
}
