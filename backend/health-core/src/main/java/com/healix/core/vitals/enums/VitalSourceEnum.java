package com.healix.core.vitals.enums;

/** 体征数据来源（库内码）。展示语义对齐 {@link com.healix.core.observation.enums.HealthDataSourceEnum}：
 * SELF≈PATIENT 用户录入，STAFF 医护代录，DEVICE 设备同步。 */
public enum VitalSourceEnum {
    /** 用户录入（历史码 SELF，等同 PATIENT） */
    SELF,
    /** 设备同步 */
    DEVICE,
    /** 医护代录 */
    STAFF;

    public static VitalSourceEnum fromOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return SELF;
        }
        String v = value.trim().toUpperCase();
        if ("PATIENT".equals(v)) {
            return SELF;
        }
        return VitalSourceEnum.valueOf(v);
    }

    public boolean matches(String value) {
        return name().equals(value);
    }
}
