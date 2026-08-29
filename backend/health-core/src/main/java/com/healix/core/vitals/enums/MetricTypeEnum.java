package com.healix.core.vitals.enums;

/** 体征指标类型 */
public enum MetricTypeEnum {
    /** 血糖 */
    BLOOD_GLUCOSE,
    /** 收缩压 */
    BLOOD_PRESSURE_SYS,
    /** 舒张压 */
    BLOOD_PRESSURE_DIA,
    /** 心率 */
    HEART_RATE,
    /** 体重 */
    WEIGHT,
    /** 步数 */
    STEPS,
    /** 睡眠时长（小时） */
    SLEEP_HOURS;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
