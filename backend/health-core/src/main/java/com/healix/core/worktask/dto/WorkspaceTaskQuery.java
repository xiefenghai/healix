package com.healix.core.worktask.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class WorkspaceTaskQuery {
    private String tenantId;
    private String orgId;
    private String pool;
    private String assigneeStaffId;
    private String taskType;
    private String status;
    private String careTeamId;
    private String keyword;
    private List<String> excludeTaskTypes;
    private LocalDateTime doneFrom;
    private LocalDateTime doneTo;
    private int offset;
    private int pageSize;
}
