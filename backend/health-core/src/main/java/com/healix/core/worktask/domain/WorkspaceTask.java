package com.healix.core.worktask.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkspaceTask extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String taskType;
    private String bizKey;
    private String openDedupKey;
    private String status;
    private String priority;
    private String assigneeStaffId;
    private String title;
    private String summary;
    private String payloadJson;
    private String source;
    private String closeReason;
    private LocalDateTime dueAt;
    private LocalDateTime openedAt;
    private LocalDateTime doneAt;
    private String doneByStaffId;
}
