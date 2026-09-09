package com.healix.core.revision.enums;

public enum RevisionBizTypeEnum {
    BASIC_ARCHIVE,
    DISEASE_ARCHIVE,
    /** 用药清单（添加/编辑/删除/处方） */
    MEDICATION,
    /** 用药依从性打卡 */
    MEDICATION_INTAKE,
    /** 指标/体征 */
    METRIC,
    /** 检验报告 */
    LAB,
    /** 检查报告 */
    EXAM,
    /** 健康管理方案 */
    CARE_PLAN,
    /** 管理方案执行打卡 */
    CARE_PLAN_CHECKIN
}
