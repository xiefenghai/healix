package com.healix.core.notify.enums;

/** 通道投递状态。 */
public enum NotifyDeliveryStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    DEAD,
    SKIPPED;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
