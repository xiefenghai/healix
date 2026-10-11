package com.healix.core.govern.enums;

/**
 * 租户配额项。{@code period} 决定用量按什么周期归零：
 * TOTAL 为存量口径（患者数、员工数），MONTH 为按月重置（AI 调用量）。
 */
public enum QuotaKeyEnum {
    /** 租户内建档患者总数 */
    PATIENT_TOTAL("在管患者数", Period.TOTAL, null),
    /** 租户内员工账号总数 */
    STAFF_TOTAL("员工账号数", Period.TOTAL, null),
    /** 每月 LLM 调用次数（助手对话 / 报告点评 / 方案生成） */
    AI_CALL_MONTHLY("AI 调用次数/月", Period.MONTH, 2000L),
    /** 每月 OCR 识别次数（检验单 / 检查单） */
    OCR_MONTHLY("OCR 识别次数/月", Period.MONTH, 500L);

    public enum Period {
        TOTAL,
        MONTH
    }

    private final String label;
    private final Period period;
    private final Long defaultLimit;

    QuotaKeyEnum(String label, Period period, Long defaultLimit) {
        this.label = label;
        this.period = period;
        this.defaultLimit = defaultLimit;
    }

    public String label() {
        return label;
    }

    public Period period() {
        return period;
    }

    /** 未在 Ops 配置过时的兜底上限；null 表示不限量。 */
    public Long defaultLimit() {
        return defaultLimit;
    }

    public static QuotaKeyEnum of(String code) {
        for (QuotaKeyEnum key : values()) {
            if (key.name().equals(code)) {
                return key;
            }
        }
        throw new IllegalArgumentException("未知配额项：" + code);
    }
}
