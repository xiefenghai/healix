package com.healix.core.notify.enums;

/** 消息受众。 */
public enum NotifyAudience {
    C_ACCOUNT,
    STAFF,
    OPS;

    public boolean matches(String value) {
        return name().equals(value);
    }

    public static NotifyAudience require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知消息受众: " + value);
        }
    }
}
