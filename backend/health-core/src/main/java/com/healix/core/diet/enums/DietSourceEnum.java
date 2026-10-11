package com.healix.core.diet.enums;

import java.util.Locale;

/** 餐次录入来源。 */
public enum DietSourceEnum {
    MANUAL,
    PHOTO_AI,
    TEXT_AI,
    STAFF;

    public static DietSourceEnum parseOrManual(String value) {
        if (value == null || value.isBlank()) {
            return MANUAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MANUAL;
        }
    }
}
