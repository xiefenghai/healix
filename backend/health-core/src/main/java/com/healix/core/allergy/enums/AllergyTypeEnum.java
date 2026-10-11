package com.healix.core.allergy.enums;

import com.healix.common.exception.BusinessException;
import java.util.Locale;

/** 过敏（免疫介导，可能危及生命）与不耐受（如乳糖不耐受）分开记录，规则据此区分提醒级别。 */
public enum AllergyTypeEnum {
    ALLERGY("过敏"),
    INTOLERANCE("不耐受");

    private final String label;

    AllergyTypeEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AllergyTypeEnum parse(String value) {
        if (value == null || value.isBlank()) {
            return ALLERGY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的过敏类型");
        }
    }
}
