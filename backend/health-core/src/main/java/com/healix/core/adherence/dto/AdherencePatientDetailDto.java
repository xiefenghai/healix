package com.healix.core.adherence.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/** 单患者依从性分析（详情页）。 */
@Data
public class AdherencePatientDetailDto {
    private LocalDate date;
    private int windowDays;
    private String peopleId;
    private String displayName;
    /** HIGH / MEDIUM / LOW */
    private String riskLevel;
    private AdherencePlanMetricsDto plan;
    private AdherenceMedMetricsDto med;
    /** 按日期升序，含查询日共 windowDays 天 */
    private List<AdherenceDayPointDto> days;
    private List<AdherenceTodayTaskItemDto> todayTasks;
    private List<AdherenceTodayMedItemDto> todayMeds;
}
