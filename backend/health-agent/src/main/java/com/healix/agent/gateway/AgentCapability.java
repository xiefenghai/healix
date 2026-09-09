package com.healix.agent.gateway;

import java.util.Locale;

public enum AgentCapability {
    CARE_PLAN("制定管理方案", true),
    OCR_LAB("检验单识别", false),
    OCR_EXAM("检查单识别", false),
    GENERAL_CHAT("健康咨询", true);

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
        try {
            return valueOf(hint.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
