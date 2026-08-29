package com.healix.core.patient.enums;

/** 性别 */
public enum GenderEnum {
    /** 男 */
    MALE,
    /** 女 */
    FEMALE,
    /** 未知 */
    UNKNOWN;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
