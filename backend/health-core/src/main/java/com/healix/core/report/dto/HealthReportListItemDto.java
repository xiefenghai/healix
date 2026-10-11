package com.healix.core.report.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HealthReportListItemDto {
    private String id;
    private String peopleId;
    private String peopleName;
    private String orgId;
    private String periodType;
    private String periodTypeLabel;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String title;
    private String status;
    private String statusLabel;
    private String staffComment;
    private String workspaceTaskId;
    private String generatedBy;
    private LocalDateTime publishedAt;
    private String publishedByStaffId;
    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;
}
