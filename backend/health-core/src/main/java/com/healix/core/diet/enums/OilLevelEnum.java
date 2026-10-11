package com.healix.core.diet.enums;

import java.util.Locale;

/** 菜肴烹调油量；按菜谱中可调油脂缩放。 */
public enum OilLevelEnum {
    LOW(0.5),
    NORMAL(1.0),
    HIGH(1.5);

    private final double factor;

    OilLevelEnum(double factor) {
        this.factor = factor;
    }

    public double factor() {
        return factor;
    }

    public static OilLevelEnum parseOrNormal(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
