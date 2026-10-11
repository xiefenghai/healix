package com.healix.core.job.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class JobDefViewDto {
    private String id;
    private String jobCode;
    private String displayName;
    private String description;
    private String cronExpr;
    private String timezone;
    private boolean enabled;
    private LocalDateTime nextFireAt;
    private LocalDateTime lastFireAt;
    private String lastStatus;
    private String lastMessage;
}
