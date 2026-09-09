package com.healix.core.adherence.dto;

import lombok.Data;

@Data
public class AdherenceBoardActionResultDto {
    /** 新建或复用的 OPEN 定期随访 */
    private String followupId;
    private String followupWorkspaceTaskId;
    private String followupType;
    private boolean followupReused;
    /** 新建或已有的打卡跟进单；未开则为 null */
    private String planNudgeTaskId;
    private boolean planNudgeCreated;
    private boolean planNudgeReused;
    private String message;
}
