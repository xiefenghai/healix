package com.healix.core.notify.enums;

/** 投递通道。 */
public enum NotifyChannel {
    IN_APP,
    SMS,
    MP_SUBSCRIBE,
    OA_TEMPLATE;

    public boolean matches(String value) {
        return name().equals(value);
    }

    public static NotifyChannel require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知消息通道: " + value);
        }
    }
}
