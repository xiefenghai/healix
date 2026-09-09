package com.healix.core.observation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record MetricViewDto(
        String id,
        String peopleId,
        String orgId,
        String metricType,
        BigDecimal value,
        String unit,
        LocalDateTime recordedAt,
        String source,
        String groupId,
        String note,
        String recordedByStaffId,
        Map<String, Object> extra,
        LocalDateTime gmtCreated) {}
