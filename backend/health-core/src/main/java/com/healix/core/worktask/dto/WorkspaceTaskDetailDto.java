package com.healix.core.worktask.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.core.followup.dto.FollowupRecordViewDto;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class WorkspaceTaskDetailDto {
    private String id;
    private String peopleId;
    private String peopleName;
    private String careTeamId;
    private String careTeamName;
    private String taskType;
    private String taskTypeLabel;
    private String title;
    private String summary;
    private String pool;
    private String status;
    private String priority;
    private String assigneeStaffId;
    private String assigneeName;
    private String doneByStaffId;
    private String doneByName;
    private String source;
    private String closeReason;
    private LocalDateTime openedAt;
    private LocalDateTime dueAt;
    private LocalDateTime doneAt;
    private String deepLink;
    private JsonNode payload;
    private List<FollowupRecordViewDto> followups;
}
