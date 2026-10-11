package com.healix.core.exercise.enums;

import java.util.Locale;

/** 运动大类；EXERCISE 任务自动打卡按大类匹配。 */
public enum ExerciseCategoryEnum {
    AEROBIC,
    RESISTANCE,
    FLEXIBILITY,
    BALANCE,
    DAILY;

    public static ExerciseCategoryEnum parseOr(String value, ExerciseCategoryEnum fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
