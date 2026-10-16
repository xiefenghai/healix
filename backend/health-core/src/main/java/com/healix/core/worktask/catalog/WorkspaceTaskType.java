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
    PLAN_REVIEW,
    /** 运动后不适症状（RED）上报 */
    EXERCISE_ALERT,
    /** 患者用药反馈：不适 / 申请调整 / 自述停药 / 过敏冲突 */
    MED_FEEDBACK,
    /** 患者自添加用药待核对（按人合并） */
    MED_VERIFY,
    /** 疾病评估统一等级升到高 / 很高 */
    ASSESSMENT_ESCALATION,
    /** 风险评估提示疑似已患病，建议确诊建档 */
    ASSESSMENT_SUSPECTED,
    /** 患者问卷覆盖了档案已有值 */
    ARCHIVE_SELF_REPORT,
    /** 发给患者的评估问卷过期未填 */
    QUESTIONNAIRE_EXPIRED;

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
            case EXERCISE_ALERT -> "运动不适";
            case MED_FEEDBACK -> "用药反馈";
            case MED_VERIFY -> "用药核对";
            case ASSESSMENT_ESCALATION -> "评估升级";
            case ASSESSMENT_SUSPECTED -> "疑似患病";
            case ARCHIVE_SELF_REPORT -> "患者自报更新";
            case QUESTIONNAIRE_EXPIRED -> "问卷未完成";
        };
    }

    /**
     * 类型默认优先级（开单写入；驾驶舱排序以类型优先级为准，患者申请等可再抬升）。
     *
     * <ul>
     *   <li>HIGH — 指标异常、入组分配、运动不适（短时限 / 阻塞 / 安全）
     *   <li>MEDIUM — 随访、打卡跟进、方案制定/复核
     *   <li>LOW — 报告审阅
     * </ul>
     */
    public WorkspaceTaskPriority defaultPriority() {
        return switch (this) {
            case METRIC_ALERT, TEAM_ASSIGN, EXERCISE_ALERT -> WorkspaceTaskPriority.HIGH;
            case FOLLOW_UP, PLAN_NUDGE, PLAN_CREATE, PLAN_REVIEW, MED_FEEDBACK, ASSESSMENT_ESCALATION,
                    ASSESSMENT_SUSPECTED -> WorkspaceTaskPriority.MEDIUM;
            case REPORT_REVIEW, MED_VERIFY, ARCHIVE_SELF_REPORT, QUESTIONNAIRE_EXPIRED -> WorkspaceTaskPriority.LOW;
        };
    }

    /**
     * 处理时限（含开单日）：到期日 = 开单日 + (dueDaysInclusive - 1) 的 23:59:59。
     * 入组、运动不适 1 天；方案 / 督促 / 异常 3 天。
     */
    public int dueDaysInclusive() {
        return switch (this) {
            case TEAM_ASSIGN, EXERCISE_ALERT -> 1;
            case PLAN_CREATE, PLAN_NUDGE, METRIC_ALERT, FOLLOW_UP, REPORT_REVIEW, PLAN_REVIEW, MED_FEEDBACK, MED_VERIFY,
                    ASSESSMENT_ESCALATION, ASSESSMENT_SUSPECTED, ARCHIVE_SELF_REPORT, QUESTIONNAIRE_EXPIRED -> 3;
        };
    }

    /** 医生公共池不可见、不可领。 */
    public boolean doctorPublicHidden() {
        return this == TEAM_ASSIGN || this == PLAN_CREATE || this == REPORT_REVIEW || this == MED_VERIFY
                || this == ARCHIVE_SELF_REPORT || this == QUESTIONNAIRE_EXPIRED;
    }

    public boolean requiresFormToClose() {
        return this == PLAN_NUDGE || this == METRIC_ALERT || this == FOLLOW_UP || this == EXERCISE_ALERT;
    }

    public static WorkspaceTaskType require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知任务类型: " + value);
        }
    }
}
