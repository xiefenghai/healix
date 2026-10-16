package com.healix.core.medication.enums;

/** 服药时段（与用药时间早/午/晚/睡前对应）。 */
public enum MedicationTimeSlotEnum {
    MORNING,
    NOON,
    EVENING,
    BEDTIME,
    /** 需要时服用：同日可多次，按 dose_seq 区分 */
    PRN,
    /** 仅历史数据：旧版每日一次统一写在此槽 */
    OTHER
}
