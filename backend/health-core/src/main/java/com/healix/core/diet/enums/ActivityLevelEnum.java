package com.healix.core.diet.enums;

import com.healix.common.exception.BusinessException;
import java.util.Locale;

/** 活动水平与 TDEE 系数。 */
public enum ActivityLevelEnum {
    SEDENTARY("久坐少动", 1.2),
    LIGHT("轻度活动", 1.375),
    MODERATE("中度活动", 1.55),
    ACTIVE("重度活动", 1.725);

    private final String label;
    private final double factor;

    ActivityLevelEnum(String label, double factor) {
        this.label = label;
        this.factor = factor;
    }

    public String label() {
        return label;
    }

    public double factor() {
        return factor;
    }

    public static ActivityLevelEnum parse(String value) {
        if (value == null || value.isBlank()) {
            return SEDENTARY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的活动水平");
        }
    }
}
