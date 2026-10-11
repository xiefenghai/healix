package com.healix.core.allergy.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @param effective 仍过敏且未被排除，参与安全校验
 * @param editableByPatient 患者自填且未经医护确认，C 端可改可删
 */
public record AllergyItemDto(
        String id,
        String category,
        String categoryLabel,
        String allergenCode,
        String allergenName,
        String allergyType,
        String allergyTypeLabel,
        List<String> reactions,
        List<String> reactionLabels,
        String reactionNote,
        String severity,
        String severityLabel,
        boolean highRisk,
        String clinicalStatus,
        String verification,
        String verificationLabel,
        String onsetText,
        LocalDate lastOccurrence,
        String source,
        String note,
        LocalDateTime verifiedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean effective,
        boolean editableByPatient) {}
