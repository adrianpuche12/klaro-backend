package balance.tenant.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identificador único de URL (ej: "pollos-hn", "pupuseria-don-carlos"). */
    @Column(nullable = false, unique = true, length = 50)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    /** starter | pro | business | enterprise */
    @Column(nullable = false, length = 20)
    private String plan = "starter";

    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public Long getId()                         { return id; }
    public void setId(Long id)                  { this.id = id; }

    public String getSlug()                     { return slug; }
    public void setSlug(String slug)            { this.slug = slug; }

    public String getName()                     { return name; }
    public void setName(String name)            { this.name = name; }

    public String getPlan()                     { return plan; }
    public void setPlan(String plan)            { this.plan = plan; }

    public Boolean getActive()                  { return active; }
    public void setActive(Boolean active)       { this.active = active; }

    public LocalDateTime getCreatedAt()         { return createdAt; }
}
