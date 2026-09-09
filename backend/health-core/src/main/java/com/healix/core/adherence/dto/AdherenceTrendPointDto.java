package com.healix.core.adherence.dto;

import java.time.LocalDate;
import lombok.Data;

/** 机构依从性趋势的一天。 */
@Data
public class AdherenceTrendPointDto {
    private LocalDate date;
    private int universeCount;
    private int followUpCount;
    private int planIncompleteCount;
    private int medIncompleteCount;
    private int streakGe3Count;
    /** 方案完成率（0–1），当日无应打任务时为空 */
    private Double planRate;
    /** 用药按次达标率（0–1），当日无应服次数时为空 */
    private Double medRate;
}
