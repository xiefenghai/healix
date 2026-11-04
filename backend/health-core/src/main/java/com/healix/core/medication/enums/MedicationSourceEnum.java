package com.healix.core.medication.enums;

/** 用药清单来源。 */
public enum MedicationSourceEnum {
    /** 手工录入（B 端） */
    MANUAL,
    /** 处方开立 */
    PRESCRIPTION,
    /** C 端患者自助添加 */
    PATIENT,
    /** 首诊 / 入组问卷采集 */
    INTAKE
}
