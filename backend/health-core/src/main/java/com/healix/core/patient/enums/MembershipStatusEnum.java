package com.healix.core.patient.enums;

/** 患者机构入组状态 */
public enum MembershipStatusEnum {
    /** 在组 */
    ACTIVE,
    /** 已退组 */
    LEFT;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
