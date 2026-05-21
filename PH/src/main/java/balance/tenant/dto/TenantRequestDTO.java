package balance.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class TenantRequestDTO {

    @NotBlank(message = "El slug es obligatorio")
    @Size(max = 50)
    @Pattern(regexp = "^[a-z0-9-]+$", message = "El slug solo puede contener letras minúsculas, números y guiones")
    private String slug;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 200)
    private String name;

    private String plan = "starter";

    public String getSlug()             { return slug; }
    public void setSlug(String slug)    { this.slug = slug; }

    public String getName()             { return name; }
    public void setName(String name)    { this.name = name; }

    public String getPlan()             { return plan; }
    public void setPlan(String plan)    { this.plan = plan; }
}
