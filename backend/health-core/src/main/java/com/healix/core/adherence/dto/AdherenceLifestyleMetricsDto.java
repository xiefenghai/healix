package com.healix.core.adherence.dto;

import lombok.Data;

/** 饮食 / 运动记录情况（近 7 日含查询日）；只读展示，不参与风险分级。 */
@Data
public class AdherenceLifestyleMetricsDto {
    private int dietDays7d;
    private int exerciseDays7d;
    /** 近 7 日活动分钟（WHO 口径：中等×1、高强度×2） */
    private int activeMin7d;
    private boolean dietToday;
    private boolean exerciseToday;
}
