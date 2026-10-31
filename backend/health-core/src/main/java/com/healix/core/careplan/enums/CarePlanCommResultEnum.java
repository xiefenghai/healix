package com.healix.core.careplan.enums;

/** 方案客户沟通结果。 */
public enum CarePlanCommResultEnum {
    ACCEPTED,
    NEED_MODIFY,
    NEED_REGENERATE;

    public static CarePlanCommResultEnum require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知沟通结果: " + value);
        }
    }
}
