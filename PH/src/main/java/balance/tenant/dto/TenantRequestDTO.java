package balance.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class TenantRequestDTO {

    @NotBlank(message = "El slug es obligatorio")
    @Size(max = 50)
    @Pattern(regexp = "^[a-z0-9-]+$", message = "El slug solo puede contener letras minusculas, numeros y guiones")
    private String slug;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 200)
    private String name;

    private String plan = "starter";
}
