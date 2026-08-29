package com.healix.core.people.enums;

/** 患者证件类型 */
public enum PeopleIdentityTypeEnum {
    /** 身份证 */
    ID_CARD,
    /** 军官证 */
    MILITARY_ID,
    /** 港澳通行证 */
    HK_MACAU_PASS,
    /** 台胞证 */
    TAIWAN_PASS,
    /** 其他 */
    OTHER;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
