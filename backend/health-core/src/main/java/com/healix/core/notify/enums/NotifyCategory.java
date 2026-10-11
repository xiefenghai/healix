package com.healix.core.notify.enums;

/** 消息类别（偏好分组）。 */
public enum NotifyCategory {
    REPORT,
    CARE_PLAN,
    ADHERENCE,
    SECURITY;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
