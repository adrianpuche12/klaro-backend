package balance.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CategoryRequestDTO {

    @NotBlank(message = "El nombre es obligatorio")
    private String name;

    private String description;

    private Integer displayOrder;
}
