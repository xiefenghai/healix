package com.healix.core.notify.catalog;

import com.healix.core.notify.enums.NotifyCategory;

/** 通知事件目录。 */
public enum NotifyEventType {
    REPORT_PUBLISHED(NotifyCategory.REPORT),
    CARE_PLAN_PUBLISHED(NotifyCategory.CARE_PLAN),
    DAILY_HEALTH_TODO(NotifyCategory.ADHERENCE),
    ACTIVATION_OTP(NotifyCategory.SECURITY),
    STAFF_NUDGE(NotifyCategory.ADHERENCE),
    /** 激励：徽章、保护卡、晚间火苗提醒（每日最多 1 条） */
    REWARD(NotifyCategory.ADHERENCE),
    /** 健管师点赞鼓励 */
    STAFF_KUDOS(NotifyCategory.ADHERENCE),
    /** 服药到点提醒 / 跟进提醒 */
    MEDICATION_REMIND(NotifyCategory.ADHERENCE),
    /** 健管师发来的评估问卷（发送 / 48 小时未填提醒） */
    ASSESSMENT_QUESTIONNAIRE(NotifyCategory.CARE_PLAN);

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
