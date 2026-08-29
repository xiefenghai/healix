package com.healix.core.org.enums;

/** 机构类型（分类标签，无权限差异） */
public enum OrgTypeEnum {
    /** 综合性医院 */
    GENERAL_HOSPITAL,
    /** 专科医院 */
    SPECIALTY_HOSPITAL,
    /** 中医综合医院 */
    TCM_GENERAL_HOSPITAL,
    /** 社区医院 */
    COMMUNITY_HOSPITAL,
    /** 其他 */
    OTHER;

    public static OrgTypeEnum fromOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL_HOSPITAL;
        }
        return OrgTypeEnum.valueOf(value.trim());
    }

    public boolean matches(String value) {
        return name().equals(value);
    }
}
