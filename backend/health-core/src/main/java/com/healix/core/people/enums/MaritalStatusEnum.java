package com.healix.core.people.enums;

/** 婚姻状况（患者人口学）。 */
public enum MaritalStatusEnum {
    UNMARRIED,
    MARRIED,
    DIVORCED,
    WIDOWED,
    OTHER;

    public String label() {
        return switch (this) {
            case UNMARRIED -> "未婚";
            case MARRIED -> "已婚";
            case DIVORCED -> "离异";
            case WIDOWED -> "丧偶";
            case OTHER -> "其他";
        };
    }

    public static MaritalStatusEnum fromStored(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("未知婚姻状况: " + value);
        }
    }
}
