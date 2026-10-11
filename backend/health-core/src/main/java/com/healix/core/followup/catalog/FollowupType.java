package com.healix.core.followup.catalog;

import java.util.Set;

/**
 * 用户可建随访类型（content_json.followupType / 业务字段 followup_type）。
 * 与 {@link FollowupRecordType} 区分：后者为记录通道 PERIODIC / METRIC_REVIEW / PLAN_NUDGE。
 */
public enum FollowupType {
    ROUTINE,
    PLAN_ADHERENCE,
    MEDICATION,
    SYMPTOM_METRIC,
    ONBOARDING,
    OTHER;

    public static final Set<String> CODES = Set.of(
            ROUTINE.name(),
            PLAN_ADHERENCE.name(),
            MEDICATION.name(),
            SYMPTOM_METRIC.name(),
            ONBOARDING.name(),
            OTHER.name());

    public String label() {
        return switch (this) {
            case ROUTINE -> "常规健康回访";
            case PLAN_ADHERENCE -> "方案执行随访";
            case MEDICATION -> "用药随访";
            case SYMPTOM_METRIC -> "指标/症状随访";
            case ONBOARDING -> "入组/首诊随访";
            case OTHER -> "其他随访";
        };
    }

    public static FollowupType require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知随访类型: " + value);
        }
    }
}
