package com.healix.core.agent.enums;

/** Agent 类型 */
public enum AgentTypeEnum {
    /** 患者助手 */
    PATIENT,
    /** 健管/医护副驾 */
    CARE_COPILOT;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
