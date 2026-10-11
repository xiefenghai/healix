package com.healix.core.diet.enums;

import com.healix.common.exception.BusinessException;
import java.util.Locale;

public enum DietGoalTypeEnum {
    MAINTAIN("维持体重"),
    LOSE("减重"),
    GAIN("增重");

    private final String label;

    DietGoalTypeEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static DietGoalTypeEnum parse(String value) {
        if (value == null || value.isBlank()) {
            return MAINTAIN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的目标类型");
        }
    }
}
