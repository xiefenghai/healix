package com.healix.core.observation.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ExamReportViewDto(
        String id,
        String peopleId,
        String orgId,
        String examType,
        LocalDateTime examinedAt,
        String conclusion,
        Map<String, Object> findings,
        String source,
        LocalDateTime gmtCreated) {}
