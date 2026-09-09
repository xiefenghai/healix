package com.healix.core.worktask.dto;

import lombok.Data;

@Data
public class WorkspaceTaskSummaryDto {
    private long publicOpenCount;
    private long mineOpenCount;
    private long doneTodayCount;
}
