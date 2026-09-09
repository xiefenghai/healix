package com.healix.core.observation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record LabOcrPrefillDto(
        String specimenType,
        LocalDateTime sampledAt,
        LocalDateTime reportedAt,
        String note,
        List<LabOcrItemDto> items,
        List<LabOcrIgnoredItemDto> ignoredItems,
        List<String> warnings) {

    public record LabOcrItemDto(
            String itemCode,
            String itemName,
            BigDecimal valueNum,
            String valueText,
            String unit,
            BigDecimal refLow,
            BigDecimal refHigh,
            String abnormalFlag) {}

    public record LabOcrIgnoredItemDto(String rawName, String reason) {}
}
