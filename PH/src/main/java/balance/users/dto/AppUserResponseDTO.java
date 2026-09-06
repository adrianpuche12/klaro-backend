package balance.users.dto;

import balance.model.Store;
import balance.users.model.AppUser;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class AppUserResponseDTO {
    private Long id;
    private String fullName;
    private String username;
    private String status;
    private Long storeId;
    private String storeName;
    private String businessRole;
    private List<String> permissions;
    private List<Long> accessibleStoreIds;
    private LocalDateTime createdAt;

    public static AppUserResponseDTO from(AppUser u) {
        AppUserResponseDTO dto = new AppUserResponseDTO();
        dto.id           = u.getId();
        dto.fullName     = u.getFullName();
        dto.username     = u.getUsername();
        dto.status       = u.getStatus() != null ? u.getStatus().name() : null;
        dto.businessRole = u.getBusinessRole();
        dto.createdAt    = u.getCreatedAt();
        if (u.getStore() != null) {
            dto.storeId   = u.getStore().getId();
            dto.storeName = u.getStore().getName();
        }
        dto.permissions = u.getPermissions().stream().sorted().toList();
        dto.accessibleStoreIds = u.getAccessibleStores().stream()
                .map(Store::getId).sorted().toList();
        return dto;
    }
}