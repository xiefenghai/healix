package com.healix.core.ops.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TenantListItem {

    private String tenantId;
    private String code;
    private String name;
    private String status;
    private boolean hasTenantAdmin;
    private LocalDateTime createdAt;
}
