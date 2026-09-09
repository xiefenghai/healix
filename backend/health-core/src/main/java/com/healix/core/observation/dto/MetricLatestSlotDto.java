package com.healix.core.observation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/** 分槽最新值：血压按 bpContext 分槽，其它按 metricType。 */
public record MetricLatestSlotDto(
        String slotKey,
        String metricType,
        String bpContext,
        String mealContext,
        BigDecimal value,
        String unit,
        LocalDateTime recordedAt,
        String id,
        String groupId,
        Map<String, Object> extra) {}
