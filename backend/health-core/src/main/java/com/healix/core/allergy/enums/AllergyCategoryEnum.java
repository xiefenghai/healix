package com.healix.core.allergy.enums;

import com.healix.common.exception.BusinessException;
import java.util.Locale;

public enum AllergyCategoryEnum {
    DRUG("药物"),
    FOOD("食物"),
    ENVIRONMENT("环境"),
    OTHER("其他");

    private final String label;

    AllergyCategoryEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AllergyCategoryEnum parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("请选择过敏类别");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的过敏类别");
        }
    }
}
