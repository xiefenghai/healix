package com.healix.core.job.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class JobRunViewDto {
    private String id;
    private String jobCode;
    private String triggerType;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String message;
    private String detailJson;
    private String instanceId;
    private String triggeredBy;
}
