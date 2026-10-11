package com.healix.core.opsstats.dto;

import lombok.Data;

/** Mapper 聚合行（办结区间 + 此刻积压）。 */
@Data
public class OpsStatsTaskAggRow {
    private long doneCount;
    private long doneWithDueCount;
    private long onTimeCount;
    private long doneNoDueCount;
    private long lateDoneCount;
    private long openCount;
    private long overdueOpenCount;
    private long cancelledOrExpiredCount;
}
