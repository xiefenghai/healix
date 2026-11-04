package com.healix.core.field;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

/** 读出的字段当前值（含来源，供问卷预填旁注）。 */
public record FieldValue(
        String code,
        JsonNode value,
        String sourceBizCode,
        String sourceClientCode,
        String sourceRefId,
        String updatedByType,
        String updatedById,
        LocalDateTime gmtModified) {}
