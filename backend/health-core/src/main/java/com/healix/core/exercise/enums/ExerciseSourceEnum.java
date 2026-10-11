package com.healix.core.exercise.enums;

import java.util.Locale;

/** 运动记录来源；SCREENSHOT_AI / TEXT_AI / DEVICE 为后续分期预留。 */
public enum ExerciseSourceEnum {
    MANUAL,
    PLAN_QUICK,
    STAFF,
    SCREENSHOT_AI,
    TEXT_AI,
    DEVICE;

    /** 患者端仅允许 MANUAL / PLAN_QUICK。 */
    public static ExerciseSourceEnum parsePatient(String value) {
        if (value == null || value.isBlank()) {
            return MANUAL;
        }
        return "PLAN_QUICK".equals(value.trim().toUpperCase(Locale.ROOT)) ? PLAN_QUICK : MANUAL;
    }
}
