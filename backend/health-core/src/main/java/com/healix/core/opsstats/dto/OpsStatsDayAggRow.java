package com.healix.core.opsstats.dto;

import java.time.LocalDate;
import lombok.Data;

@Data
public class OpsStatsDayAggRow {
    private LocalDate statsDay;
    private long doneCount;
    private long doneWithDueCount;
    private long onTimeCount;
}
