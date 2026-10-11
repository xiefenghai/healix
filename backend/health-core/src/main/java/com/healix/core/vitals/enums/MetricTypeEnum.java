package com.healix.core.vitals.enums;

/** 体征/指标类型 */
public enum MetricTypeEnum {
    BLOOD_GLUCOSE,
    BLOOD_PRESSURE_SYS,
    BLOOD_PRESSURE_DIA,
    HEART_RATE,
    HEIGHT,
    WEIGHT,
    WAIST,
    TEMPERATURE,
    STEPS,
    SLEEP_HOURS;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
