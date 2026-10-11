package com.healix.core.allergy.enums;

/** 患者级过敏史采集状态。 */
public enum AllergyStatusEnum {
    UNKNOWN("未询问"),
    NKA("无已知过敏"),
    HAS("有过敏");

    private final String label;

    AllergyStatusEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
