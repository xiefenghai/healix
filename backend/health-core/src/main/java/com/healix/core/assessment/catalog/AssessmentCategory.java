package com.healix.core.assessment.catalog;

/** 评估两大类：未确诊看患病风险，确诊后看严重等级分层（同一病种二选一）。 */
public enum AssessmentCategory {
    RISK("患病风险评估"),
    SEVERITY("疾病严重等级分层");

    private final String label;

    AssessmentCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static String labelOf(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "RISK", "INCIDENT_RISK" -> RISK.label;
            case "SEVERITY", "CONTROL_STATUS" -> SEVERITY.label;
            default -> code;
        };
    }
}
