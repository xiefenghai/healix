package com.healix.core.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class HealthReportViewDto {
    private String id;
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String peopleName;
    private String careTeamId;
    private String periodType;
    private String periodTypeLabel;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String title;
    private String status;
    private String statusLabel;
    private Integer schemaVersion;
    private JsonNode content;
    private String staffComment;
    private String workspaceTaskId;
    private LocalDateTime publishedAt;
    private String publishedByStaffId;
    private String publishedByName;
    private String generatedBy;
    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;
}
