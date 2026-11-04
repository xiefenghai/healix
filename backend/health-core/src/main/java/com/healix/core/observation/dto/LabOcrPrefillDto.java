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
        List<String> warnings,
        String imageKey) {

    public LabOcrPrefillDto(
            String specimenType,
            LocalDateTime sampledAt,
            LocalDateTime reportedAt,
            String note,
            List<LabOcrItemDto> items,
            List<LabOcrIgnoredItemDto> ignoredItems,
            List<String> warnings) {
        this(specimenType, sampledAt, reportedAt, note, items, ignoredItems, warnings, null);
    }

    public LabOcrPrefillDto withImageKey(String imageKey) {
        return new LabOcrPrefillDto(
                specimenType, sampledAt, reportedAt, note, items, ignoredItems, warnings, imageKey);
    }

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
