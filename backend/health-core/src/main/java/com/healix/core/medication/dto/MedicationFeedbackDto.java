package com.healix.core.medication.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 患者用药反馈视图。 */
public record MedicationFeedbackDto(
        String id,
        String medicationId,
        String drugName,
        String kind,
        String kindLabel,
        List<String> symptoms,
        List<String> symptomLabels,
        String severity,
        String content,
        String status,
        String reply,
        String handledByName,
        LocalDateTime handledAt,
        String workTaskId,
        LocalDateTime gmtCreated) {}
