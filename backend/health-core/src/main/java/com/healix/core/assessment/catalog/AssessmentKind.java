package com.healix.core.assessment.catalog;

/**
 * 评估能力类型（库内编码稳定；对外展示用 {@link #displayName()}）。
 *
 * <ul>
 *   <li>{@link #INCIDENT_RISK} → 疾病风险等级评估（筛查向：尚未确诊该病时的发病风险分层）
 *   <li>{@link #SEVERITY} → 疾病分层评估（管理向：已确诊后的病情管理分层，非临床诊断分期）
 *   <li>{@link #CONTROL_STATUS} → 控制状态分标（管理向：已确诊后的达标/风险色标）
 * </ul>
 */
public enum AssessmentKind {
    /** 疾病风险等级评估 */
    INCIDENT_RISK,
    /** 疾病分层评估（管理分层，非诊断） */
    SEVERITY,
    /** 控制状态分标（红/黄/绿等） */
    CONTROL_STATUS;

    public String displayName() {
        return switch (this) {
            case INCIDENT_RISK -> "疾病风险等级评估";
            case SEVERITY -> "疾病分层评估";
            case CONTROL_STATUS -> "控制状态分标";
        };
    }

    public static String displayNameOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return AssessmentKind.valueOf(code.trim()).displayName();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }
}
