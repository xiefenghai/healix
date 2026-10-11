package com.healix.core.followup.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FollowupRecord extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String workspaceTaskId;
    private String recordType;
    private String source;
    private String status;
    private String title;
    private String summary;
    private LocalDateTime plannedAt;
    private LocalDateTime dueAt;
    private String assigneeStaffId;
    private String contactChannel;
    private String contactResult;
    private String contentJson;
    private String cancelReason;
    private LocalDateTime completedAt;
    private String completedByStaffId;
}
