package balance.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AppUserRequestDTO {

    @NotBlank(message = "El nombre completo es obligatorio")
    private String fullName;

    @NotBlank(message = "El username es obligatorio")
    private String username;

    @NotBlank(message = "La contraseña es obligatoria")
    private String password;

    @NotNull(message = "El local es obligatorio")
    private Long storeId;

    /** Rol a asignar: "user" (default), "admin" (solo root puede asignarlo). */
    private String role = "user";

    public String getFullName()         { return fullName; }
    public void setFullName(String v)   { this.fullName = v; }

    public String getUsername()         { return username; }
    public void setUsername(String v)   { this.username = v; }

    public String getPassword()         { return password; }
    public void setPassword(String v)   { this.password = v; }

    public Long getStoreId()            { return storeId; }
    public void setStoreId(Long v)      { this.storeId = v; }

    public String getRole()             { return role; }
    public void setRole(String v)       { this.role = v; }
}
