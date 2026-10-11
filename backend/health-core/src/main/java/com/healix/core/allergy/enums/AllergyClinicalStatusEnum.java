package com.healix.core.allergy.enums;

import com.healix.common.exception.BusinessException;
import java.util.Locale;

public enum AllergyClinicalStatusEnum {
    ACTIVE("仍过敏"),
    RESOLVED("已消失");

    private final String label;

    AllergyClinicalStatusEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AllergyClinicalStatusEnum parse(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的过敏状态");
        }
    }
}
