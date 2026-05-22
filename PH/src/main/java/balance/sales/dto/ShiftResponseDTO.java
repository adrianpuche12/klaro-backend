package balance.sales.dto;

import balance.sales.model.Shift;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ShiftResponseDTO {
    private Long id;
    private String code;
    private String username;
    private String status;
    private Long storeId;
    private String storeName;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;

    public static ShiftResponseDTO from(Shift s) {
        ShiftResponseDTO dto = new ShiftResponseDTO();
        dto.id        = s.getId();
        dto.code      = s.getCode();
        dto.username  = s.getUsername();
        dto.status    = s.getStatus() != null ? s.getStatus().name() : null;
        dto.openedAt  = s.getOpenedAt();
        dto.closedAt  = s.getClosedAt();
        if (s.getStore() != null) {
            dto.storeId   = s.getStore().getId();
            dto.storeName = s.getStore().getName();
        }
        return dto;
    }
}