package com.healix.core.opsstats.dto;

import lombok.Data;

@Data
public class OpsStatsStaffRowDto {
    /** null 表示未分配。 */
    private String staffId;
    private String staffName;
    private long doneCount;
    private long doneWithDueCount;
    private long onTimeCount;
    private Double onTimeRate;
    private long overdueOpenCount;
    private long followupDoneCount;
}
