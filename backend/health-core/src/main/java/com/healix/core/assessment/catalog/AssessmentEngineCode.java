package com.healix.core.assessment.catalog;

/** V1 / 预留引擎编码。 */
public enum AssessmentEngineCode {
    CDRS,
    OBESITY_SCREEN,
    /** 高血压：血压分级 + 易患 + 心脑血管风险（并列三结果） */
    HYPERTENSION_RISK,
    /** 糖尿病血糖控制分标（红/黄/绿/准绿/无标） */
    DIABETES_CONTROL_LABEL,
    /** 官方工具结果录入（非本地公式） */
    CHINA_PAR
}
