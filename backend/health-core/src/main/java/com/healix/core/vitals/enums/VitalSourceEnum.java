package com.healix.core.vitals.enums;

/** 体征数据来源 */
public enum VitalSourceEnum {
    /** 患者自填 */
    SELF,
    /** 设备上报 */
    DEVICE,
    /** 员工代录 */
    STAFF;

    public static VitalSourceEnum fromOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return SELF;
        }
        return VitalSourceEnum.valueOf(value.trim());
    }

    public boolean matches(String value) {
        return name().equals(value);
    }
}
