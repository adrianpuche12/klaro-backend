package balance.users.dto;

import balance.users.model.Role;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class RoleResponseDTO {
    private Long id;
    private String name;
    private int level;
    private boolean canManageUsers;
    private List<String> permissions;
    private LocalDateTime createdAt;

    public static RoleResponseDTO from(Role r) {
        RoleResponseDTO dto = new RoleResponseDTO();
        dto.id             = r.getId();
        dto.name           = r.getName();
        dto.level          = r.getLevel();
        dto.canManageUsers = r.isCanManageUsers();
        dto.permissions    = r.getPermissions().stream().sorted().toList();
        dto.createdAt      = r.getCreatedAt();
        return dto;
    }
}
