package com.healix.core.opsstats.dto;

import lombok.Data;

@Data
public class OpsStatsTypeSliceDto {
    private String taskType;
    private String taskTypeLabel;
    private long doneCount;
}
