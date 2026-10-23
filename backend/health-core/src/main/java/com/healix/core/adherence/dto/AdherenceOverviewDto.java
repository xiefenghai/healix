package com.healix.core.adherence.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class AdherenceOverviewDto {
    private LocalDate date;
    private String careTeamId;
    private int universeCount;
    private int followUpCount;
    private int planIncompleteCount;
    private int medIncompleteCount;
    private int streakGe3Count;
    /** 近 7 日饮食、运动都无记录的人数 */
    private int lifestyleInactiveCount;

    /** 统计日方案应打任务数、已完成数；完成率 0–1，无应打时为空 */
    private int planDueSum;
    private int planDoneSum;
    private Double planRate;
    /** 统计日用药应服次数、已服次数；达标率 0–1，无应服时为空 */
    private int medDueDoseSum;
    private int medTakenDoseSum;
    private Double medRate;
    /** 统计日有饮食 / 运动记录的人数 */
    private int dietLoggedCount;
    private int exerciseLoggedCount;
    /** 各组对比；未入组单独一行。完成率从低到高。 */
    private List<AdherenceTeamSliceDto> teams = new ArrayList<>();
}
