package com.healix.core.opsstats.dto;

import lombok.Data;

/** Mapper：按办理人聚合。 */
@Data
public class OpsStatsStaffAggRow {
    private String staffId;
    private String staffName;
    private long doneCount;
    private long doneWithDueCount;
    private long onTimeCount;
    private long overdueOpenCount;
    private long followupDoneCount;
}
