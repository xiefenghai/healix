package com.healix.core.care.enums;

/** 健管组成员类型 */
public enum CareTeamMemberTypeEnum {
    /** 员工（医生/健管师） */
    STAFF,
    /** 患者 */
    PATIENT;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
