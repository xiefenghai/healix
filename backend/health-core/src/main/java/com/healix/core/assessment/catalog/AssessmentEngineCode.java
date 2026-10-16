package com.healix.core.assessment.catalog;

/** 引擎编码。CHINA_PAR 已下线，仅用于历史快照展示。 */
public enum AssessmentEngineCode {
    CDRS,
    HYPERTENSION_RISK,
    OBESITY_RISK,
    DIABETES_SEVERITY,
    HYPERTENSION_SEVERITY,
    OBESITY_SEVERITY,
    CHINA_PAR;

    public static String legacyLabel(String code) {
        if (CHINA_PAR.name().equals(code)) {
            return "心脑血管风险（China-PAR·官方结果，已下线）";
        }
        return code;
    }
}
