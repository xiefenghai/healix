package com.healix.core.adherence.dto;

import lombok.Data;

/** 当日方案任务明细。 */
@Data
public class AdherenceTodayTaskItemDto {
    private String taskId;
    private String title;
    private String category;
    private String frequency;
    private String timeSlot;
    /** DONE / SKIPPED / PENDING */
    private String status;
}
