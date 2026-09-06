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

    private String role = "user";

    /** Etiqueta de negocio libre (ej. "ENCARGADO", "CONTADOR", "SOCIO"). Opcional. */
    private String businessRole;

    /** Módulos habilitados (valores de PermissionModule). Opcional — si se omite,
     * el usuario queda sin acceso a módulos restringidos hasta que un admin se los asigne. */
    private List<String> permissions;

    /** Locales adicionales accesibles (más allá del local principal). Opcional. */
    private List<Long> storeIds;
}
