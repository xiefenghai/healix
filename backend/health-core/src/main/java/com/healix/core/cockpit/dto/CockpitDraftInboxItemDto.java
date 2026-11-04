package com.healix.core.cockpit.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 驾驶舱右栏机构级「待我确认」：我名下的方案 / 报告类待办，跨患者聚合。 */
@Data
public class CockpitDraftInboxItemDto {
    private String taskId;
    private String taskType;
    private String taskTypeLabel;
    /** CARE_PLAN / REPORT */
    private String kind;
    private String peopleId;
    /** 报告审阅时为报告 ID */
    private String reportId;
    private String displayName;
    private String summary;
    private boolean overdue;
    private LocalDateTime dueAt;
    /** 打开抽屉 mode：care-plan / reports */
    private String sheetMode;
    private String actionLabel;
}
