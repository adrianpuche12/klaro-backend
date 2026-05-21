package balance.tenant.dto;

import balance.tenant.model.Tenant;

import java.time.LocalDateTime;

public class TenantResponseDTO {

    private Long id;
    private String slug;
    private String name;
    private String plan;
    private Boolean active;
    private LocalDateTime createdAt;

    public static TenantResponseDTO from(Tenant t) {
        TenantResponseDTO dto = new TenantResponseDTO();
        dto.id        = t.getId();
        dto.slug      = t.getSlug();
        dto.name      = t.getName();
        dto.plan      = t.getPlan();
        dto.active    = t.getActive();
        dto.createdAt = t.getCreatedAt();
        return dto;
    }

    public Long getId()                 { return id; }
    public String getSlug()             { return slug; }
    public String getName()             { return name; }
    public String getPlan()             { return plan; }
    public Boolean getActive()          { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
