package com.healix.core.allergy.enums;

import com.healix.common.exception.BusinessException;
import java.util.Locale;

public enum AllergyVerificationEnum {
    UNCONFIRMED("患者自述"),
    CONFIRMED("已确认"),
    REFUTED("已排除");

    private final String label;

    AllergyVerificationEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AllergyVerificationEnum parse(String value, AllergyVerificationEnum fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的核实状态");
        }
    }
}
