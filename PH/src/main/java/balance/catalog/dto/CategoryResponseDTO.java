package balance.catalog.dto;

import balance.catalog.model.Category;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class CategoryResponseDTO {

    private Long id;
    private String name;
    private String description;
    private Boolean active;
    private Integer displayOrder;
    private Long parentId;
    private Long storeId;
    private long productCount;
    private List<CategoryResponseDTO> children;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CategoryResponseDTO from(Category category, long productCount) {
        CategoryResponseDTO dto = new CategoryResponseDTO();
        dto.id           = category.getId();
        dto.name         = category.getName();
        dto.description  = category.getDescription();
        dto.active       = category.getActive();
        dto.displayOrder = category.getDisplayOrder();
        dto.parentId     = category.getParent() != null ? category.getParent().getId() : null;
        dto.storeId      = category.getStore() != null ? category.getStore().getId() : null;
        dto.productCount = productCount;
        dto.createdAt    = category.getCreatedAt();
        dto.updatedAt    = category.getUpdatedAt();
        dto.children     = new ArrayList<>(); // el service agrega los hijos
        return dto;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Boolean getActive() { return active; }
    public Integer getDisplayOrder() { return displayOrder; }
    public Long getParentId() { return parentId; }
    public Long getStoreId() { return storeId; }
    public long getProductCount() { return productCount; }
    public List<CategoryResponseDTO> getChildren() { return children; }
    public void setChildren(List<CategoryResponseDTO> children) { this.children = children; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
