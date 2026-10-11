package com.healix.core.followup.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class FollowupRecordViewDto {
    private String id;
    private String recordType;
    private String recordTypeLabel;
    private String source;
    private String status;
    private String title;
    private String summary;
    private String workspaceTaskId;
    private JsonNode content;
    private String contactChannel;
    private String contactResult;
    private String completedByStaffId;
    private String completedByName;
    private LocalDateTime completedAt;
    private LocalDateTime plannedAt;
    private LocalDateTime dueAt;
}
