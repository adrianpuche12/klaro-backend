package balance.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Alta de una empresa nueva por el administrador de plataforma (SPRINT-16).
 * Nada tiene valor por defecto de un cliente en particular: moneda y zona horaria son obligatorias.
 */
@Getter
@Setter
public class TenantOnboardingRequestDTO {

    // ── Empresa ──────────────────────────────────────────────────────────────
    @NotBlank(message = "El nombre de la empresa es obligatorio")
    @Size(max = 200)
    private String name;

    @NotBlank(message = "El slug es obligatorio")
    @Size(min = 3, max = 50, message = "El slug debe tener entre 3 y 50 caracteres")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "El slug solo puede contener letras minusculas, numeros y guiones")
    private String slug;

    @Pattern(regexp = "^[a-z]{1,20}$", message = "Plan invalido")
    private String plan = "starter";

    // ── Configuración inicial (TenantConfig) ─────────────────────────────────
    @NotBlank(message = "La moneda es obligatoria")
    @Pattern(regexp = "^[A-Z]{3}$", message = "La moneda debe ser un codigo ISO de 3 letras (ej. USD, HNL, ARS)")
    private String currency;

    @NotBlank(message = "La zona horaria es obligatoria")
    @Size(max = 50)
    private String timezone;

    private String address;

    @Size(max = 30)
    private String phone;

    // ── Usuario dueño (root) de la empresa ───────────────────────────────────
    @NotBlank(message = "El usuario del dueño es obligatorio")
    @Size(max = 100)
    private String rootUsername;

    @NotBlank(message = "El nombre del dueño es obligatorio")
    @Size(max = 200)
    private String rootFullName;

    @NotBlank(message = "La contraseña del dueño es obligatoria")
    @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
    private String rootPassword;
}
