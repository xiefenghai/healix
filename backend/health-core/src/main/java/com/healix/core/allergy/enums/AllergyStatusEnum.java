package com.healix.core.allergy.enums;

/** 患者级过敏史采集状态。 */
public enum AllergyStatusEnum {
    /** 尚未勾选采集状态（与「显式无过敏」区分） */
    UNKNOWN("未采集"),
    /** 已显式确认无已知过敏 */
    NKA("没有过敏"),
    HAS("有过敏");

    private final String label;

    AllergyStatusEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
