package balance.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Vista resumida de un local para el dashboard del admin. */
@Getter @Setter
public class StoreDashboardDTO {

    private Long    storeId;
    private String  storeName;
    private boolean hasActiveShift;
    private String  shiftCode;
    private String  shiftUsername;
    private LocalDateTime shiftOpenedAt;
    private long    shiftSalesCount;
    private BigDecimal shiftSalesTotal;
    private long       totalProducts;
    private long       lowStockCount;
    private BigDecimal estimatedValue;
}
