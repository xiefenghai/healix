package com.healix.core.assessment.catalog;

import java.util.Arrays;

/** 评估病种：每个病种一对引擎（风险 / 分层）。 */
public enum AssessmentDisease {
    DIABETES("diabetes", "糖尿病"),
    HYPERTENSION("hypertension", "高血压"),
    OBESITY("obesity", "肥胖");

    private final String code;
    private final String label;

    AssessmentDisease(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static AssessmentDisease ofCode(String code) {
        return Arrays.stream(values()).filter(d -> d.code.equalsIgnoreCase(code)).findFirst().orElse(null);
    }
}
