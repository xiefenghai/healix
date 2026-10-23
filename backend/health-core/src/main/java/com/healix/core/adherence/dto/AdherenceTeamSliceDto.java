package com.healix.core.adherence.dto;

import lombok.Data;

/** 一个健管组（或未入组）在统计日的依从汇总。 */
@Data
public class AdherenceTeamSliceDto {
    /** 未入组时为空 */
    private String careTeamId;
    private String careTeamName;
    private int patientCount;
    private int planDueSum;
    private int planDoneSum;
    /** 方案任务完成率 0–1；当日无应打任务时为空 */
    private Double planRate;
    private int medDueDoseSum;
    private int medTakenDoseSum;
    /** 用药按次达标率 0–1；无应服次数时为空 */
    private Double medRate;
    private int followUpCount;
    private int planIncompleteCount;
    private int medIncompleteCount;
}
