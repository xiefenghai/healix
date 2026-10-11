package com.healix.core.worktask.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WorkspaceTaskListItemDto {
    private String id;
    private String peopleId;
    private String peopleName;
    private String careTeamId;
    private String careTeamName;
    private String taskType;
    private String taskTypeLabel;
    private String summary;
    private String pool;
    private String status;
    private String priority;
    private String assigneeStaffId;
    private String assigneeName;
    private String doneByStaffId;
    private String doneByName;
    private LocalDateTime openedAt;
    private LocalDateTime dueAt;
    private LocalDateTime doneAt;
    private String deepLink;
    /** 异常单汇总的测量条数（METRIC_ALERT）。 */
    private int hitCount;
    /** @deprecated 兼容旧前端；等于 max(0, hitCount-1) */
    private int suppressedCount;
}
