package com.healix.core.notify.catalog;

import com.healix.core.notify.enums.NotifyCategory;

/** 通知事件目录。 */
public enum NotifyEventType {
    REPORT_PUBLISHED(NotifyCategory.REPORT),
    CARE_PLAN_PUBLISHED(NotifyCategory.CARE_PLAN),
    DAILY_HEALTH_TODO(NotifyCategory.ADHERENCE),
    ACTIVATION_OTP(NotifyCategory.SECURITY),
    STAFF_NUDGE(NotifyCategory.ADHERENCE);

    private final NotifyCategory category;

    NotifyEventType(NotifyCategory category) {
        this.category = category;
    }

    public NotifyCategory category() {
        return category;
    }

    public boolean matches(String value) {
        return name().equals(value);
    }

    public static NotifyEventType require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知通知事件: " + value);
        }
    }
}
