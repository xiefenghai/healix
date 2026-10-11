package com.healix.core.exercise.enums;

import java.util.Locale;

/** 运动强度（谈话测试三档）。 */
public enum IntensityEnum {
    /** 轻松，能唱歌 */
    LIGHT(0),
    /** 能说话但不能唱歌 */
    MODERATE(1),
    /** 说不完整句子 */
    VIGOROUS(2);

    /** 活跃分钟倍数（WHO：中等 ×1、高强度 ×2、低强度不计） */
    private final int activeFactor;

    IntensityEnum(int activeFactor) {
        this.activeFactor = activeFactor;
    }

    public int activeFactor() {
        return activeFactor;
    }

    public static IntensityEnum parseOr(String value, IntensityEnum fallback) {
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
