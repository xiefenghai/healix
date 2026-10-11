package com.healix.core.followup.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class FollowupListItemDto {
    private String id;
    private String peopleId;
    private String peopleName;
    private String recordType;
    private String recordTypeLabel;
    private String source;
    private String status;
    private String title;
    private String summary;
    private String followupType;
    private String followupTypeLabel;
    private String workspaceTaskId;
    private String contactChannel;
    private String assigneeStaffId;
    private String assigneeName;
    private String completedByStaffId;
    private String completedByName;
    private LocalDateTime plannedAt;
    private LocalDateTime dueAt;
    private LocalDateTime completedAt;
    private LocalDateTime gmtCreated;
}
