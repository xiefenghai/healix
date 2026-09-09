package com.healix.core.worktask.catalog;

/** 工作台任务类型。 */
public enum WorkspaceTaskType {
    TEAM_ASSIGN,
    PLAN_CREATE,
    PLAN_NUDGE,
    METRIC_ALERT,
    FOLLOW_UP,
    REPORT_REVIEW,
    /** 方案医生复核（临床签署） */
    PLAN_REVIEW;

    public boolean matches(String value) {
        return name().equals(value);
    }

    public String label() {
        return switch (this) {
            case TEAM_ASSIGN -> "分配健管组";
            case PLAN_CREATE -> "制定方案";
            case PLAN_NUDGE -> "打卡跟进";
            case METRIC_ALERT -> "指标异常";
            case FOLLOW_UP -> "定期随访";
            case REPORT_REVIEW -> "报告审阅";
            case PLAN_REVIEW -> "方案复核";
        };
    }

    /**
     * 处理时限（含开单日）：到期日 = 开单日 + (dueDaysInclusive - 1) 的 23:59:59。
     * 入组 1 天；方案 / 督促 / 异常 3 天。
     */
    public int dueDaysInclusive() {
        return switch (this) {
            case TEAM_ASSIGN -> 1;
            case PLAN_CREATE, PLAN_NUDGE, METRIC_ALERT, FOLLOW_UP, REPORT_REVIEW, PLAN_REVIEW -> 3;
        };
    }

    /** 医生公共池不可见、不可领。 */
    public boolean doctorPublicHidden() {
        return this == TEAM_ASSIGN || this == PLAN_CREATE || this == REPORT_REVIEW;
    }

    public boolean requiresFormToClose() {
        return this == PLAN_NUDGE || this == METRIC_ALERT || this == FOLLOW_UP;
    }

    public static WorkspaceTaskType require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知任务类型: " + value);
        }
    }
}
