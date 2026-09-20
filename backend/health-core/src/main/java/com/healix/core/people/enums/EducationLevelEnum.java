package com.healix.core.people.enums;

/** 文化程度（患者人口学）。 */
public enum EducationLevelEnum {
    ILLITERATE,
    PRIMARY,
    JUNIOR,
    SENIOR,
    COLLEGE,
    BACHELOR,
    MASTER_PLUS,
    OTHER;

    public String label() {
        return switch (this) {
            case ILLITERATE -> "文盲或半文盲";
            case PRIMARY -> "小学";
            case JUNIOR -> "初中";
            case SENIOR -> "高中/中专";
            case COLLEGE -> "大专";
            case BACHELOR -> "本科";
            case MASTER_PLUS -> "硕士及以上";
            case OTHER -> "其他";
        };
    }

    public static EducationLevelEnum fromStored(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("未知文化程度: " + value);
        }
    }
}
