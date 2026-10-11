package com.healix.core.adherence.dto;

import java.time.LocalDate;
import lombok.Data;

/**
 * 单患者依从性摘要（实时轻量查询，供详情 Hero / 驾驶舱等）。
 *
 * <p>主百分比 {@link #percent} = 近 7 日方案任务完成率（与看板 rate7d 同口径）。
 */
@Data
public class AdherencePatientSummaryDto {
    private String peopleId;
    private LocalDate date;
    /** 方案完成率窗口天数（固定 7） */
    private int windowDays;
    /**
     * 近 N 日方案任务完成率 0–100；无执行中方案或窗口内无应打任务时为 null。
     */
    private Integer percent;
    /** 原始完成率 0~1，与 {@link AdherencePlanMetricsDto#getRate7d()} 一致 */
    private Double rate;
    private boolean hasActivePlan;
    private int streakDays;
    /** HIGH / MEDIUM / LOW */
    private String riskLevel;
    private boolean planTodayIncomplete;
    private boolean medTodayIncomplete;
    /**
     * 当日用药按次完成率 0–100；无应服次数时为 null。
     */
    private Integer medPercent;
}
