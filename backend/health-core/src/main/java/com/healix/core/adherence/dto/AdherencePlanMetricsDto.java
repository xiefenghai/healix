package com.healix.core.adherence.dto;

import lombok.Data;

@Data
public class AdherencePlanMetricsDto {
    /** 查询日是否存在执行中的方案（ACTIVE 且落在当前版本 horizon 内） */
    private boolean hasActivePlan;
    private int due;
    private int done;
    private int skipped;
    private int incomplete;
    private boolean todayIncomplete;
    private int streakDays;
    /** 0~1；无应打任务时为 null */
    private Double rate7d;
}
