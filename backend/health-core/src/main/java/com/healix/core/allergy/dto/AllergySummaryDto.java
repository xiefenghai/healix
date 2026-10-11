package com.healix.core.allergy.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @param status UNKNOWN / NKA / HAS
 * @param bannerLabels 生效条目的展示名（高危在前），用于患者头部过敏标识
 * @param pendingCount 生效但仍为患者自述、待医护确认的条数
 */
public record AllergySummaryDto(
        String status,
        String statusLabel,
        LocalDateTime reviewedAt,
        String reviewedByStaffId,
        boolean highRisk,
        int pendingCount,
        List<String> bannerLabels,
        List<AllergyItemDto> items) {}
