package balance.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class StoreRequestDTO {

    @NotBlank(message = "El nombre es obligatorio")
    private String name;

    private String address;

    private String phone;
}
