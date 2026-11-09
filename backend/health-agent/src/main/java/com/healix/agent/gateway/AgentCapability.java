package com.healix.agent.gateway;

import java.util.Locale;

public enum AgentCapability {
    CREATE_ARCHIVE("患者建档", true),
    CARE_PLAN("制定管理方案", true),
    OCR_LAB("检验单识别", true),
    OCR_EXAM("检查单识别", true),
    OCR_MED("用药单识别", true),
    REPORT_SUMMARY("管理报告点评", true),
    CHAT_EXTRACT("整理这段记录", true),
    /** 对健管师展示为业务问答，避免暴露 GENERAL_CHAT 代码名 */
    GENERAL_CHAT("业务问答", true);

    private final String label;
    private final boolean enabled;

    AgentCapability(String label, boolean enabled) {
        this.label = label;
        this.enabled = enabled;
    }

    public String label() {
        return label;
    }

    public boolean enabled() {
        return enabled;
    }

    public static AgentCapability fromHint(String hint) {
        if (!org.springframework.util.StringUtils.hasText(hint)) {
            return null;
        }
        String normalized = hint.trim().toUpperCase(Locale.ROOT);
        // 软入口标记：不当硬能力（交给 ReAct）
        if (isSoftHint(normalized)) {
            return null;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 软入口：无芯片硬 hint / INTENT_AUTO / REACT → 走 ReAct。
     * 芯片点选的 CREATE_ARCHIVE、CARE_PLAN 等返回 false。
     */
    public static boolean isSoftHint(String hint) {
        if (!org.springframework.util.StringUtils.hasText(hint)) {
            return true;
        }
        String h = hint.trim().toUpperCase(Locale.ROOT);
        return "INTENT_AUTO".equals(h) || "AUTO".equals(h) || "REACT".equals(h);
    }
}
