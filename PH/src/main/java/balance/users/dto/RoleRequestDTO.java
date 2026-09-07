package balance.users.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class RoleRequestDTO {

    @NotBlank(message = "El nombre es obligatorio")
    private String name;

    @Min(value = 1, message = "El nivel debe ser mayor a 0")
    private int level;

    private boolean canManageUsers;

    /** Módulos habilitados (valores de PermissionModule). */
    private List<String> permissions;
}
