package balance.users.dto;

import balance.users.model.AppUser;

import java.time.LocalDateTime;

public class AppUserResponseDTO {

    private Long          id;
    private String        fullName;
    private String        username;
    private String        status;
    private Long          storeId;
    private String        storeName;
    private LocalDateTime createdAt;

    public static AppUserResponseDTO from(AppUser u) {
        AppUserResponseDTO dto = new AppUserResponseDTO();
        dto.id        = u.getId();
        dto.fullName  = u.getFullName();
        dto.username  = u.getUsername();
        dto.status    = u.getStatus() != null ? u.getStatus().name() : null;
        dto.createdAt = u.getCreatedAt();
        if (u.getStore() != null) {
            dto.storeId   = u.getStore().getId();
            dto.storeName = u.getStore().getName();
        }
        return dto;
    }

    public Long getId()                 { return id; }
    public String getFullName()         { return fullName; }
    public String getUsername()         { return username; }
    public String getStatus()           { return status; }
    public Long getStoreId()            { return storeId; }
    public String getStoreName()        { return storeName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
