package com.healix.core.allergy.enums;

import com.healix.common.exception.BusinessException;
import java.util.Locale;

public enum AllergySeverityEnum {
    MILD("轻度"),
    MODERATE("中度"),
    SEVERE("重度"),
    UNKNOWN("不详");

    private final String label;

    AllergySeverityEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AllergySeverityEnum parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的严重程度");
        }
    }
}
