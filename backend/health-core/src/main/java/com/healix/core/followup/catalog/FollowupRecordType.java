package com.healix.core.followup.catalog;

/** 随访记录类型（原 follow_up_form + 定期随访）。 */
public enum FollowupRecordType {
    /** 打卡跟进处理（原 PLAN_NUDGE 回执） */
    PLAN_NUDGE,
    /** 指标异常处理（原 METRIC_REVIEW） */
    METRIC_REVIEW,
    /** 定期/临床随访 */
    PERIODIC;

    public boolean matches(String value) {
        return name().equals(value);
    }

    public String label() {
        return switch (this) {
            case PLAN_NUDGE -> "打卡跟进";
            case METRIC_REVIEW -> "指标异常处理";
            case PERIODIC -> "定期随访";
        };
    }

    public static FollowupRecordType require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知随访类型: " + value);
        }
    }

    public static FollowupRecordType forWorkspaceTask(String taskType) {
        if ("PLAN_NUDGE".equals(taskType)) {
            return PLAN_NUDGE;
        }
        if ("METRIC_ALERT".equals(taskType)) {
            return METRIC_REVIEW;
        }
        if ("FOLLOW_UP".equals(taskType)) {
            return PERIODIC;
        }
        throw new IllegalArgumentException("该任务类型不通过随访关单: " + taskType);
    }
}
