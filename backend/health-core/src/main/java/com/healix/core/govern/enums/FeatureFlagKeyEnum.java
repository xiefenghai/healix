package com.healix.core.govern.enums;

/**
 * 功能开关。租户级记录优先，其次平台级（tenant_id 为空）记录，最后落到 {@link #defaultEnabled()}。
 *
 * <p>只登记「值得单独售卖或需要灰度」的能力；基础档案/随访这类核心功能不做开关，
 * 免得线上被误关成不可用系统。
 */
public enum FeatureFlagKeyEnum {
    /** C 端发现助手对话 */
    AI_PATIENT_AGENT("C 端健康助手", true),
    /** B 端 Care Copilot（方案生成等） */
    AI_STAFF_COPILOT("B 端 Care Copilot", true),
    /** 检验单 / 检查单拍照识别 */
    AI_OCR("报告拍照识别", true),
    /** 管理报告 AI 点评草稿 */
    AI_REPORT_SUMMARY("报告 AI 点评", true),
    /** C 端自助申请回访 */
    PATIENT_FOLLOWUP_REQUEST("C 端申请回访", true),
    /** 租户白标（Logo / 主色 / 应用名） */
    WHITE_LABEL("白标定制", false),
    /** 方案发布后需医生复核签署 */
    DOCTOR_PLAN_REVIEW("方案医生复核", false);

    private final String label;
    private final boolean defaultEnabled;

    FeatureFlagKeyEnum(String label, boolean defaultEnabled) {
        this.label = label;
        this.defaultEnabled = defaultEnabled;
    }

    public String label() {
        return label;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public static FeatureFlagKeyEnum of(String code) {
        for (FeatureFlagKeyEnum key : values()) {
            if (key.name().equals(code)) {
                return key;
            }
        }
        throw new IllegalArgumentException("未知功能开关：" + code);
    }
}
