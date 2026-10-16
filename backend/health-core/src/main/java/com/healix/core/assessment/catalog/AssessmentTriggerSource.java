package com.healix.core.assessment.catalog;

public enum AssessmentTriggerSource {
    MANUAL,
    /** 输入变更或每日全量自动重算 */
    AUTO,
    /** 问卷提交后重算 */
    QUESTIONNAIRE,
    /** 旧数据 */
    JOB
}
