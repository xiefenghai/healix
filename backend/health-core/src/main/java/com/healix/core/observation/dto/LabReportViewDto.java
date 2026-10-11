package com.healix.core.observation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record LabReportViewDto(
        String id,
        String peopleId,
        String orgId,
        String specimenType,
        LocalDateTime sampledAt,
        LocalDateTime reportedAt,
        String source,
        String note,
        List<LabItemViewDto> items,
        LocalDateTime gmtCreated) {

    public record LabItemViewDto(
            String id,
            String itemCode,
            String itemName,
            BigDecimal valueNum,
            String valueText,
            String unit,
            BigDecimal refLow,
            BigDecimal refHigh,
            String abnormalFlag) {}
}
