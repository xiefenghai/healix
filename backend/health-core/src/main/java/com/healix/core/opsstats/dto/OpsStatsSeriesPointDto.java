package com.healix.core.opsstats.dto;

import lombok.Data;

@Data
public class OpsStatsSeriesPointDto {
    private String date;
    private long doneCount;
    private long doneWithDueCount;
    private long onTimeCount;
    /** 0–100；无 due 办结当日则为 null。 */
    private Double onTimeRate;
}
