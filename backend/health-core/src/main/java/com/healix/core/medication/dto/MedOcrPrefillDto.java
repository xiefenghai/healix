package com.healix.core.medication.dto;

import java.time.LocalDate;
import java.util.List;

/** 用药单 / 处方 OCR 预填，供人工确认后开立处方。 */
public record MedOcrPrefillDto(List<MedOcrItemDto> items, List<String> warnings) {

    public record MedOcrItemDto(
            String drugName,
            String usageMethod,
            String frequency,
            String doseAmount,
            String doseUnit,
            String timingNote,
            Integer courseDays,
            LocalDate startDate) {}
}
